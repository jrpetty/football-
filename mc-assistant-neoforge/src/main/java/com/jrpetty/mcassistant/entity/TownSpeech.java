package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [culture2] How a town talks: walk from one town into the next and you hear it.
 * <ul>
 * <li><b>Greetings and farewells</b> of its land ("Fair winds!" on the coast, "Steady stone" in the hills, "Walk quiet"
 *     in the woods, "Good harvest to you" on the plains), and of its temper ("Blessings" in a devout town, "Stand fast"
 *     in a martial one, "Good trading" in a merchant one). A folk waving to a player, passing one in the street, saying
 *     hello at the start of a talk or goodbye at its end says them, most of the time.</li>
 * <li><b>Pet words.</b> Each land has its own words for the stores, the board and the coin: on the coast the stores are
 *     "the hold" and a coin "a shell"; in the hills "the vault" and "a mark". Said in a town's bubbles, they are its
 *     own; the screens and the books keep the plain words, so nothing is ever unclear.</li>
 * <li><b>Sayings</b> coined out of the town's own chronicle: "as steady as the north gate" from a raid held off,
 *     "dry as day 40" from the drought, "lucky as Ember" from the first diamond, "happy as Bram and Wick" from a
 *     wedding: up to ten, five at the least (a young town fills the rest with its land's old sayings), each with where
 *     it came from. A new one goes into the chronicle. Folk use them in small talk, in gossip and in their answers.</li>
 * <li><b>Nicknames</b> its folk earn by their deeds: "Bram Ironhand" (forty pieces at the forge), "Fen the Swift" (a
 *     hundred and fifty loads carried), "Wick Netcaster", "Old Moss". On the folk's card, in the chronicle when the
 *     town takes one up, and in the others' talk.</li>
 * </ul>
 */
public final class TownSpeech {

    private TownSpeech() {}

    /** At least so many sayings a town has (filled from its land's own if its history is young), and at most. */
    static final int FEWEST = 5, MOST = 10;

    /** A saying: what it says, the day it was coined (or -1, the land's own), and what it came from. */
    public record Saying(String text, long day, String from) {
        String[] row() { return new String[]{ text, Long.toString(day), from }; }
    }

    private static final Map<UUID, List<Saying>> SAYINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, String>> NICKS = new ConcurrentHashMap<>();

    static void resetForTests() {
        SAYINGS.clear();
        NICKS.clear();
    }

    // ------------------------------------------------------------------ greetings

    /** The land's hello and goodbye. */
    static String[] landWords(Homeland.Land l) {
        return switch (l) {
            case COAST -> new String[]{ "Fair winds", "Fair winds to you!", "Mind the tide." };
            case RIVER -> new String[]{ "Good water", "Fair crossing!", "Mind the ford." };
            case FOREST -> new String[]{ "Walk quiet", "Walk quiet.", "Mind the roots." };
            case TAIGA -> new String[]{ "Pine and plenty", "Stay by the fire.", "Mind the snow-load." };
            case SNOW -> new String[]{ "Warm hearth", "Keep warm!", "Mind the ice." };
            case MOUNTAIN -> new String[]{ "Steady stone", "Steady stone.", "Keep your footing." };
            case DESERT -> new String[]{ "Water and shade", "May your well run deep.", "Shade find you." };
            case SAVANNA -> new String[]{ "Wide skies", "Safe grazing!", "Wide skies to you." };
            case JUNGLE -> new String[]{ "Green and growing", "Mind the vines!", "Green and growing." };
            case SWAMP -> new String[]{ "Dry boots", "Keep your feet dry!", "Dry boots to you." };
            case BADLANDS -> new String[]{ "Red rock, steady hand", "Shade find you.", "Mind the sun." };
            case MEADOW -> new String[]{ "Bloom and bee", "Sweet days!", "Bloom and bee." };
            case PLAINS -> new String[]{ "Good harvest to you", "Sun on your fields!", "Good harvest." };
        };
    }

    /** The temper's hello. */
    static String temperWords(Values.Value v) {
        return switch (v) {
            case TRADITION -> "Blessings";
            case SAFETY -> "Stand fast";
            case WEALTH -> "Good trading";
            case PROGRESS -> "Bright days";
            case LEISURE -> "Easy days";
            case FOOD -> "Full larder";
            case HOMES -> "Warm hearth";
        };
    }

    /** The town's own hello: its land's ("Fair winds"). */
    public static String greeting(UUID village) {
        return landWords(Homeland.of(village))[0];
    }

    /** Its temper's hello ("Blessings"). */
    public static String temperGreeting(UUID village) {
        return temperWords(TownWays.heart(village));
    }

    /** Its farewell ("Fair winds to you!"). */
    public static String farewell(UUID village) {
        return landWords(Homeland.of(village))[1];
    }

    /** Tests: the hello this folk would call out to somebody of this name, its town's way (the land's, or the temper's). */
    public static String helloForTests(VillageFolkEntity f, String name, boolean temper) {
        UUID id = f.ownerId();
        return (temper ? temperGreeting(id) : greeting(id)) + ", " + name + "!";
    }

    /** Has this town's land been looked over (and so its tongue its own)? */
    private static boolean known(@Nullable UUID village) {
        return village != null && Homeland.known(village) != null;
    }

    /**
     * A hello to a player (Greetings.wave): the town's own most of the time, in place of "Morning, Alex!". A child, a
     * grump that does not much like them, or a folk whose land is not known yet keeps to what it was going to say.
     */
    public static String hello(VillageFolkEntity f, Player p, String said) {
        UUID id = f.ownerId();
        if (!known(id) || f.isBaby()) return said;
        RandomSource r = f.getRandom();
        int aff = f.persona().affinity(p.getUUID());
        if (aff <= -15 || f.life().has(Social.Trait.GRUMPY) && aff < 30 || r.nextInt(10) < 3) return said;
        String you = p.getName().getString();
        String hi = r.nextInt(3) == 0 ? temperGreeting(id) : greeting(id);
        return f.life().has(Social.Trait.SHY) ? hi + ", " + you + "…" : aff >= 55 ? hi + ", " + you + "! Good to see you." : hi + ", " + you + "!";
    }

    /** A word for a player walking past (FolkTalk.passing): the town's hello, half the time; null for the usual. */
    @Nullable
    public static String passing(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        if (!known(id) || f.isBaby() || f.getRandom().nextBoolean()) return null;
        int aff = f.persona().affinity(p.getUUID());
        if (aff <= -15) return null;
        return (f.getRandom().nextInt(3) == 0 ? temperGreeting(id) : greeting(id)) + (aff >= 20 ? ", " + p.getName().getString() + "." : ".");
    }

    /** Goodbye at the end of a talk (FolkTalk.bye): the town's farewell, most of the time; null for the usual. */
    @Nullable
    public static String bye(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        if (!known(id) || f.getRandom().nextInt(10) < 3) return null;
        if (f.persona().affinity(p.getUUID()) <= -15) return null;
        String[] w = landWords(Homeland.of(id));
        return f.getRandom().nextBoolean() ? w[1] : w[2];
    }

    // ------------------------------------------------------------------ pet words

    /** The land's own words for the stores, the board, a coin and coins. */
    static String[] pet(Homeland.Land l) {
        return switch (l) {
            case COAST -> new String[]{ "the hold", "the mast", "shell", "shells" };
            case RIVER -> new String[]{ "the boathouse", "the ferry-post", "penny", "pennies" };
            case FOREST -> new String[]{ "the lodge-store", "the notice-tree", "acorn", "acorns" };
            case TAIGA -> new String[]{ "the woodshed", "the blaze", "bit", "bits" };
            case SNOW -> new String[]{ "the ice-house", "the slate", "chip", "chips" };
            case MOUNTAIN -> new String[]{ "the vault", "the stone", "mark", "marks" };
            case DESERT -> new String[]{ "the cool-store", "the tablet", "piece", "pieces" };
            case SAVANNA -> new String[]{ "the kraal", "the herd-post", "token", "tokens" };
            case JUNGLE -> new String[]{ "the long-house", "the totem", "bead", "beads" };
            case SWAMP -> new String[]{ "the stilt-store", "the post", "groat", "groats" };
            case BADLANDS -> new String[]{ "the strongroom", "the slab", "nugget", "nuggets" };
            case MEADOW -> new String[]{ "the granary", "the hive-board", "penny", "pennies" };
            case PLAINS -> new String[]{ "the barn", "the board", "penny", "pennies" };
        };
    }

    /** The town's own words, or null before its land is known. */
    @Nullable
    public static String[] petWords(@Nullable UUID village) {
        Homeland.Land l = village == null ? null : Homeland.known(village);
        return l == null ? null : pet(l);
    }

    private static final Pattern STORES = Pattern.compile("\\b([Tt])he (storehouse|stores)\\b");
    private static final Pattern BOARD = Pattern.compile("\\b([Tt])he board\\b");
    private static final Pattern COINS = Pattern.compile("\\bcoins\\b");
    private static final Pattern COIN = Pattern.compile("\\bcoin\\b");

    /**
     * Words said out loud, in the town's own way (FolkTalk.speak): its own words for the stores, the board and the coin.
     * Only in its bubbles: the screens and the books keep the plain words.
     */
    public static String local(VillageFolkEntity f, String text) {
        String[] w = petWords(f.ownerId());
        if (w == null || text == null || text.isEmpty()) return text;
        return localise(text, w);
    }

    /** Tests and local: these words, in a town of this land. */
    public static String localise(String text, String[] w) {
        String out = replace(STORES, text, w[0]);
        out = replace(BOARD, out, w[1]);
        out = COINS.matcher(out).replaceAll(Matcher.quoteReplacement(w[3]));
        out = COIN.matcher(out).replaceAll(Matcher.quoteReplacement(w[2]));
        return out;
    }

    private static String replace(Pattern p, String text, String word) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = m.group(1).equals("T") ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ sayings

    private static final Pattern GATE = Pattern.compile("raiders came at the (\\w+) gate");
    private static final Pattern DIAMOND = Pattern.compile("^([A-Z][a-z]+)\\b.*first diamond");
    private static final Pattern WED = Pattern.compile("^([A-Z][a-z]+) and ([A-Z][a-z]+) were wed");
    private static final Pattern OLD = Pattern.compile("^([A-Z][a-z]+) died peacefully in their sleep, aged (\\d+)");
    private static final Pattern DREAM = Pattern.compile("^([A-Z][a-z]+)'s dream came true");
    private static final Pattern RISING = Pattern.compile("^(.+?) is rising");
    private static final Pattern PEACE = Pattern.compile("and (.+?) made peace");
    private static final Pattern WAR = Pattern.compile("declared war on (.+?),");
    private static final Pattern OPENED = Pattern.compile("^(?:the )?(.+?) was opened$");
    private static final Pattern TWINS = Pattern.compile("^([A-Z][a-z]+) and ([A-Z][a-z]+) had twins");
    private static final Pattern SAVED = Pattern.compile("^(\\w+) saved ([A-Z][a-z]+) from");
    private static final Pattern AGE = Pattern.compile("(stone|iron|diamond|nether) age");

    /**
     * A saying coined from one line of the chronicle, with the kind of thing it came from (so a town has one of each
     * kind before it has two); null if the line coins none.
     */
    @Nullable
    static String[] coin(UUID village, Chronicle.Entry e) {
        String home = TownWays.of(Villages.name(village));
        String text = e.text();
        String t = text.toLowerCase(Locale.ROOT);
        long d = e.day() + 1;
        Matcher m;
        if ((m = GATE.matcher(t)).find()) {
            return t.contains("of the village fell")
                ? new String[]{ "raid", "true as the watch on day " + d }
                : new String[]{ "raid", "steady as the " + m.group(1) + " gate held" };
        }
        if (t.contains("raiders came")) return new String[]{ "raid", "steady as the watch on day " + d };
        if (t.startsWith("a drought:")) return new String[]{ "drought", "dry as day " + d };
        if (t.contains("the drought broke")) return new String[]{ "rain", "welcome as the rain of day " + d };
        if (t.contains("first diamond") && (m = DIAMOND.matcher(text)).find()) return new String[]{ "diamond", "lucky as " + m.group(1) };
        if (t.contains("a great storm broke")) return new String[]{ "storm", "loud as the storm of day " + d };
        if (t.startsWith("the fire burnt") || t.startsWith("fire ")) return new String[]{ "fire", "quick as the bucket line on day " + d };
        if ((m = RISING.matcher(text)).find() && !t.contains("raider")) return new String[]{ "flood", "high as " + m.group(1).toLowerCase(Locale.ROOT)
            .replaceFirst("^the ", "the ") + " on day " + d };
        if (t.contains("the levee was finished")) return new String[]{ "levee", "solid as " + home + " levee" };
        if ((m = WED.matcher(text)).find()) return new String[]{ "wedding", "happy as " + m.group(1) + " and " + m.group(2) };
        if ((m = OLD.matcher(text)).find() && Integer.parseInt(m.group(2)) >= 50) return new String[]{ "old", "old as " + m.group(1) };
        if ((m = DREAM.matcher(text)).find()) return new String[]{ "dream", "as sure as " + m.group(1) + "'s dream" };
        if ((m = PEACE.matcher(text)).find()) return new String[]{ "peace", "sweet as the peace of day " + d };
        if ((m = WAR.matcher(text)).find()) return new String[]{ "war", "fierce as the war with " + m.group(1) };
        if ((m = TWINS.matcher(text)).find()) return new String[]{ "twins", "double trouble, like " + m.group(1) + "'s twins" };
        if ((m = SAVED.matcher(text)).find()) return new String[]{ "saved", "safe as " + m.group(2) + " with " + m.group(1) + " by" };
        if (t.contains("settlers left to found")) return new String[]{ "settlers", "bold as the settlers of day " + d };
        if (t.startsWith("settlers from")) return new String[]{ "founded", "far as the road from home" };
        if (t.contains("the town's mine was opened")) return new String[]{ "mine", "deep as " + home + " mine" };
        if ((m = AGE.matcher(t)).find() && (t.contains("came into") || t.contains("entered") || t.contains("reached"))) {
            return new String[]{ "age", "bright as the coming of the " + capital(m.group(1)) + " Age" };
        }
        if ((m = OPENED.matcher(text)).find() && m.group(1).length() < 24) return new String[]{ "built", "snug as the " + m.group(1)
            .toLowerCase(Locale.ROOT).replaceFirst("^the ", "") };
        return null;
    }

    /** The land's own old sayings, for a town whose history is young. */
    static String[] landSayings(Homeland.Land l) {
        return switch (l) {
            case COAST -> new String[]{ "sure as the tide", "salt in the blood", "a fish a day keeps the hold full", "calm as a mill-pond sea", "the sea gives and the sea takes" };
            case RIVER -> new String[]{ "slow as the river", "all water runs home", "steady as the ford", "the river remembers", "a full net and a dry bank" };
            case FOREST, TAIGA -> new String[]{ "quiet as the deep wood", "every oak was an acorn", "sound as heartwood", "the woods keep their own counsel", "sharp as a winter axe" };
            case SNOW -> new String[]{ "slow as a thaw", "warm as a shared fire", "the cold finds the idle", "white as the first fall", "stout as a snow-wall" };
            case MOUNTAIN, BADLANDS -> new String[]{ "hard as the crag", "stone remembers", "steady as bedrock", "the mountain takes its time", "deep as the first shaft" };
            case DESERT -> new String[]{ "dry as the noon wind", "a full well is a full town", "patient as sand", "shade is worth gold", "cool as the cistern" };
            case SAVANNA -> new String[]{ "wide as the sky", "a fat herd and a full pot", "slow as an acacia grows", "the grass comes back", "dust in the boots" };
            case JUNGLE -> new String[]{ "green as the canopy", "everything grows", "loud as the parrots", "the vine finds a way", "wet as the morning" };
            case SWAMP -> new String[]{ "slow as the fen", "deep as the mire", "dry boots are a blessing", "the fen keeps its secrets", "a frog in every pond" };
            case MEADOW -> new String[]{ "sweet as clover", "busy as the bees", "pink as the blossom", "the hive feeds the town", "soft as the meadow grass" };
            case PLAINS -> new String[]{ "flat as the plains", "a good harvest mends all", "early to the field", "the wheat waits for nobody", "a full barn sleeps sound" };
        };
    }

    /** The town's sayings: coined from its chronicle, its land's after, at least five. */
    public static List<Saying> sayings(UUID village) {
        List<Saying> had = SAYINGS.get(village);
        if (had != null) return had;
        List<Saying> out = new ArrayList<>();
        for (String[] r : Culture.rows(village, TownWays.PREFIX + "sayings")) {
            if (r.length < 3) continue;
            out.add(new Saying(r[0], Culture.num(r[1], -1), r[2]));
        }
        SAYINGS.put(village, List.copyOf(out));
        return SAYINGS.get(village);
    }

    /** Only those coined from what happened here (not the land's own). */
    public static List<Saying> coined(UUID village) {
        List<Saying> out = new ArrayList<>();
        for (Saying s : sayings(village)) if (s.day() >= 0) out.add(s);
        return out;
    }

    /**
     * The town's tongue looked over (once a day, TownWays.daily): new sayings coined from what has happened since, one
     * of each kind of thing first, up to ten; the land's own to make up five; and the nicknames its folk have earned.
     */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        coinSayings(v.id(), day);
        nicknames(level, v, day);
    }

    static void coinSayings(UUID village, long day) {
        List<Saying> have = new ArrayList<>(sayings(village));
        Set<String> texts = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (Saying s : have) {
            texts.add(s.text());
            if (s.day() >= 0) kinds.add(s.from().split(":", 2)[0]);
        }
        // The land's own make room for what really happened here.
        List<Saying> coinedNow = new ArrayList<>();
        Map<String, Integer> perKind = new HashMap<>();
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String[] c = coin(village, e);
            if (c == null || texts.contains(c[1])) continue;
            int n = perKind.getOrDefault(c[0], 0) + (kinds.contains(c[0]) ? 1 : 0);
            if (n >= 2) continue;
            perKind.merge(c[0], 1, Integer::sum);
            texts.add(c[1]);
            coinedNow.add(new Saying(c[1], e.day(), c[0] + ": " + e.text()));
        }
        // One of each kind first, the rest after, the oldest first within each.
        coinedNow.sort((a, b) -> {
            boolean ka = kinds.contains(a.from().split(":", 2)[0]), kb = kinds.contains(b.from().split(":", 2)[0]);
            return ka != kb ? (ka ? 1 : -1) : Long.compare(a.day(), b.day());
        });
        boolean changed = false;
        Saying newest = null;
        for (Saying s : coinedNow) {
            if (coinedCount(have) >= MOST) break;
            have.add(s);
            newest = s;
            changed = true;
        }
        // The land's own give way to the town's: none once five are its own, and never more than ten in all.
        if (coinedCount(have) >= FEWEST) {
            if (have.removeIf(x -> x.day() < 0)) changed = true;
        } else {
            while (have.size() > MOST && landCount(have) > 0) {
                for (int i = 0; i < have.size(); i++) if (have.get(i).day() < 0) { have.remove(i); break; }
                changed = true;
            }
        }
        String[] land = landSayings(Homeland.of(village));
        for (int i = 0; i < land.length && have.size() < FEWEST; i++) {
            if (texts.contains(land[i])) continue;
            texts.add(land[i]);
            have.add(new Saying(land[i], -1, "the land's own"));
            changed = true;
        }
        if (!changed) return;
        List<String[]> rows = new ArrayList<>();
        for (Saying s : have) rows.add(s.row());
        Culture.rows(village, TownWays.PREFIX + "sayings", rows);
        SAYINGS.put(village, List.copyOf(have));
        if (newest != null) Villages.tell(village, day, "a new saying went round the town: \"" + newest.text() + "\"");
    }

    private static int coinedCount(List<Saying> l) {
        int n = 0;
        for (Saying s : l) if (s.day() >= 0) n++;
        return n;
    }

    private static int landCount(List<Saying> l) {
        return l.size() - coinedCount(l);
    }

    /** Tests: the town's sayings coined now, from its chronicle as it stands. */
    public static List<Saying> coinForTests(UUID village, long day) {
        coinSayings(village, day);
        return sayings(village);
    }

    /** One of the town's sayings, for talk; the ones from its own history twice as likely. Null with none. */
    @Nullable
    public static String saying(@Nullable UUID village, RandomSource r) {
        if (village == null) return null;
        List<Saying> all = sayings(village);
        if (all.isEmpty()) return null;
        List<Saying> pick = new ArrayList<>(all);
        pick.addAll(coined(village));
        return pick.get(r.nextInt(pick.size())).text();
    }

    /**
     * A saying worked into an answer (FolkTalk.manner), now and then: "…As we say in Ashhaven: steady as the north
     * gate held." Only when it is answering a question about itself or the town.
     */
    public static String flavour(VillageFolkEntity f, String said, boolean answering) {
        if (!answering || said.isEmpty() || f.isBaby() || f.getRandom().nextInt(6) != 0) return said;
        String s = saying(f.ownerId(), f.getRandom());
        if (s == null) return said;
        return said + " " + FolkTalk.pick(f.getRandom(), "As we say in " + Villages.name(f.ownerId()) + ": " + s + ".",
            "Still — " + s + ", as they say here.", capital(s) + ", as the old ones say.");
    }

    /** What two folk say of the town's ways in passing (Smalltalk): a saying, a nickname, the town's dish. */
    public static List<String[]> talk(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        List<String[]> out = new ArrayList<>();
        UUID id = a.ownerId();
        if (id == null || !known(id)) return out;
        String s = saying(id, r);
        if (s != null) {
            out.add(new String[]{ FolkTalk.pick(r, "Busy, busy. " + capital(s) + ", eh?", "Good day for it. " + capital(s) + ".",
                "How goes it? Me, I'm " + s + "."), FolkTalk.pick(r, "Ha! " + capital(s) + " — just so.", "As the old ones say.", "Ha!"), "" });
        }
        String nick = nickname(b);
        if (nick != null) {
            out.add(new String[]{ greeting(id) + ", " + nick + "!", FolkTalk.pick(r, "Oh, stop it.", "That's me!", "They'll never let me forget it."),
                FolkTalk.pick(r, "You earned it.", "") });
        }
        Cuisine.Dish d = Cuisine.dishOf(id);
        if (d != null) {
            out.add(new String[]{ "Is there " + d.words + " at the feast tonight?", FolkTalk.pick(r, "There'd better be.",
                "There always is.", "If the cook has the makings."), FolkTalk.pick(r, "Can't beat it.", "") });
        }
        return out;
    }

    /** Gossip of the town's nicknames (FolkTalk.gossipFor): who it calls what now, and why. */
    public static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        UUID id = f.ownerId();
        if (id == null) return out;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity o) || o == f) continue;
            String nick = nickname(o);
            if (nick == null) continue;
            out.add("Everybody calls " + o.displayNameCap() + " \"" + nick + "\" now. " + (nick.startsWith("Old ")
                ? "Not to their face, mind." : "Earned it, too."));
            if (out.size() >= 2) break;
        }
        return out;
    }

    /** What a folk tells a player of the town's sayings ("Any sayings?"). */
    static String talkSayings(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "Not that I know of.";
        List<Saying> mine = coined(id);
        RandomSource r = f.getRandom();
        if (mine.isEmpty()) {
            String s = saying(id, r);
            return s == null ? "Not that I know of." : "The old ones say \"" + s + "\". We've not lived long enough here for sayings of our own.";
        }
        Saying s = mine.get(r.nextInt(mine.size()));
        String from = s.from().contains(": ") ? s.from().substring(s.from().indexOf(": ") + 2) : s.from();
        return "We say \"" + s.text() + "\" here. It comes from day " + (s.day() + 1) + ", when " + from + "."
            + (mine.size() > 1 ? " And \"" + mine.get((mine.indexOf(s) + 1) % mine.size()).text() + "\", but that's another story." : "");
    }

    // ------------------------------------------------------------------ nicknames

    /** The nickname this folk's deeds have earned it, by its town's reckoning; null if none yet. */
    @Nullable
    static String earned(VillageFolkEntity f) {
        if (f.isBaby()) return null;
        String n = f.displayNameCap();
        int at = n.indexOf(' ');
        if (at > 0) n = n.substring(0, at);
        AssistantEntity.StationTask t = f.stationTask();
        if (t == AssistantEntity.StationTask.SMITH && f.deedCount(AssistantEntity.Deed.THINGS_MADE) >= 30) return n + " Ironhand";
        if (t == AssistantEntity.StationTask.GUARD && f.deedCount(AssistantEntity.Deed.MOBS_KILLED) >= 12) return n + " the Bold";
        if (t == AssistantEntity.StationTask.MINE && f.deedCount(AssistantEntity.Deed.ORE_FOUND) >= 25) return n + " Deepdelver";
        if (t == AssistantEntity.StationTask.MINE && f.deedCount(AssistantEntity.Deed.BLOCKS_MINED) >= 3000) return n + " Stonebreaker";
        if (t == AssistantEntity.StationTask.FISH && f.deedCount(AssistantEntity.Deed.FISH_CAUGHT) >= 60) return n + " Netcaster";
        if (t == AssistantEntity.StationTask.FARM && f.deedCount(AssistantEntity.Deed.CROPS_HARVESTED) >= 400) return n + " Greenthumb";
        if (t == AssistantEntity.StationTask.WOOD && f.deedCount(AssistantEntity.Deed.TREES_FELLED) >= 60) return n + " Axeswing";
        if (t == AssistantEntity.StationTask.HAUL && f.deedCount(AssistantEntity.Deed.LOADS_HAULED) >= 150) return n + " the Swift";
        if (t == AssistantEntity.StationTask.RANCH && f.deedCount(AssistantEntity.Deed.ANIMALS_BRED) >= 30) return n + " Shepherd";
        if (t == AssistantEntity.StationTask.COOK && f.deedCount(AssistantEntity.Deed.THINGS_MADE) >= 30) return n + " Ladle";
        if (f.deedCount(AssistantEntity.Deed.BLOCKS_BUILT) >= 1500) return n + " the Builder";
        if (f.isOld()) return "Old " + n;
        return null;
    }

    /** The folk's nickname, as its town calls it; null if it has none. */
    @Nullable
    public static String nickname(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        return nicks(id).get(f.getUUID().toString());
    }

    private static Map<String, String> nicks(UUID village) {
        return NICKS.computeIfAbsent(village, k -> {
            Map<String, String> m = new ConcurrentHashMap<>();
            for (String[] r : Culture.rows(village, TownWays.PREFIX + "nicks")) if (r.length >= 2) m.put(r[0], r[1]);
            return m;
        });
    }

    /** The nicknames the town's folk have earned, worked out (once a day): a new one goes into the chronicle. */
    static int nicknames(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<String, String> m = nicks(id);
        int fresh = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            String nick = earned(f);
            String had = m.get(f.getUUID().toString());
            if (nick == null || nick.equals(had)) continue;
            m.put(f.getUUID().toString(), nick);
            fresh++;
            if (fresh <= 2) Villages.tell(id, day, "the town has taken to calling " + f.displayNameCap() + " \"" + nick + "\"");
            f.persona().remember(day, "they call me " + nick + " now", 3);
        }
        if (fresh > 0) {
            List<String[]> rows = new ArrayList<>();
            for (Map.Entry<String, String> e : m.entrySet()) rows.add(new String[]{ e.getKey(), e.getValue() });
            Culture.rows(id, TownWays.PREFIX + "nicks", rows);
        }
        return fresh;
    }

    /** Tests: the town's nicknames worked out now. How many were new. */
    public static int nicknamesForTests(ServerLevel level, Villages.Village v) {
        return nicknames(level, v, level.getDayTime() / 24000L);
    }

    /** "What do they call you?" */
    static String talkNick(VillageFolkEntity f) {
        String nick = nickname(f);
        if (nick == null) return "Just " + f.displayNameCap() + ". I've not done enough to earn anything else — yet.";
        return "\"" + nick + "\". " + (nick.startsWith("Old ") ? "Cheek! I'm not that old." : "I earned it, mind.");
    }

    // ------------------------------------------------------------------ words

    /** The town's tongue, for its page. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        if (!known(village)) {
            out.add("Its tongue waits on its land being looked over.");
            return out;
        }
        String[] w = landWords(Homeland.of(village));
        String[] p = pet(Homeland.of(village));
        out.add("Its folk say \"" + w[0] + "\" (and, being " + TownWays.temperWord(TownWays.heart(village)) + ", \"" + temperGreeting(village)
            + "\"); goodbye is \"" + w[1] + "\".");
        out.add("Its own words: the stores are \"" + p[0] + "\", the board \"" + p[1] + "\", a coin \"" + FolkTalk.article(p[2]) + "\".");
        List<Saying> s = sayings(village);
        for (Saying x : s) {
            String from = x.day() < 0 ? "the land's own" : "day " + (x.day() + 1) + ": " + (x.from().contains(": ")
                ? x.from().substring(x.from().indexOf(": ") + 2) : x.from());
            out.add("\"" + capital(x.text()) + "\" — " + from + ".");
        }
        List<String> named = new ArrayList<>(nicks(village).values());
        if (!named.isEmpty()) out.add("Nicknames: " + String.join(", ", named.size() > 8 ? named.subList(0, 8) : named) + ".");
        return out;
    }

    private static String capital(String s) {
        return TownWays.capital(s);
    }
}
