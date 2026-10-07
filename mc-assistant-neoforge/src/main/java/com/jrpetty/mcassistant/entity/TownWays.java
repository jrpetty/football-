package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [culture2] A town's own ways: what it eats, how it talks, how it builds, the feast it keeps and what it holds
 * sacred. Walk out of a coast town that greets you "Fair winds!", whose houses are white-walled with blue glass in the
 * windows and whose cook has a fish stew on the café's counter, and into a hill town that says "Steady stone", lives
 * behind stone walls under a crenellated eave and eats hotpot, and you know you have crossed a border.
 * <ul>
 * <li><b>Its table</b> (Cuisine): a dish of its own, from what its land gives, and a second from what its folk bring in
 *     most; cooked from the stores by the game's own recipe, served first at its feasts, sold at the café and the
 *     tavern, carried to its neighbours as a delicacy, and taken to a colony and made there in the colony's way.</li>
 * <li><b>Its tongue</b> (TownSpeech): its greetings and farewells (the land's, and its temper's), its own words for the
 *     stores, the board and the coin, its sayings coined out of its own chronicle, and the nicknames its folk earn.</li>
 * <li><b>Its building</b> (Architecture): one of six styles, chosen at the founding from its land, its founders and
 *     its temper, that changes real blocks in its houses, its lamp posts and its benches.</li>
 * <li><b>Its feast</b> (TownFeast): a festival of its own, from what it is known for, with its own decorations, music,
 *     food and contest.</li>
 * <li><b>Its faith</b> (Beliefs): what it holds sacred, and so how it buries its dead, where it marries, what it calls
 *     its children, what it will not do, and the rites its elder leads.</li>
 * </ul>
 *
 * <p>Each is worked out from real things: the land (Homeland), what its founders cared about and what its folk care
 * about now (its temper: the value most of its grown folk put first, Values), what it has lived through (the
 * chronicle), what its hands bring in, and what it is rich enough to afford. It is all worked out on a slow clock,
 * once a game day (and on the events that matter: a colony founded, a death), and kept in the town's ledger, so a
 * restart forgets none of it. Its style and its faith can drift as the town changes, slowly, and the chronicle says
 * so when they do.
 *
 * <p>Where the player sees it: the Culture page of the town's books (its "Our own ways" sections), a line on the
 * board, a line on each folk's card ("Known as Bram Ironhand; a true harbour-town soul"), the folk's talk and bubbles,
 * the chronicle, and /village ways.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class TownWays {

    private TownWays() {}

    /** The ledger's notes are kept under this. */
    static final String PREFIX = "ways.";

    /** The day each town's ways were last worked out (in memory: a restart works them out again, from the ledger). */
    private static final Map<UUID, Long> WORKED = new ConcurrentHashMap<>();
    /** Each town's temper, as last worked out: the value most of its grown folk put first. */
    private static final Map<UUID, Values.Value> HEART = new ConcurrentHashMap<>();

    /** Everything kept in memory forgotten (Culture.resetForTests: the tests share one JVM). */
    public static void resetForTests() {
        WORKED.clear();
        HEART.clear();
        Cuisine.resetForTests();
        TownSpeech.resetForTests();
        Architecture.resetForTests();
        TownFeast.resetForTests();
        Beliefs.resetForTests();
    }

    // ------------------------------------------------------------------ the ledger

    @Nullable
    static String note(UUID village, String key) {
        String s = Ledger.note(village, PREFIX + key);
        return s == null || s.isEmpty() ? null : s;
    }

    static void note(UUID village, String key, @Nullable String value) {
        if (value == null) Ledger.forget(village, PREFIX + key);
        else Ledger.note(village, PREFIX + key, value);
    }

    // ------------------------------------------------------------------ the town's clock

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 13) return;
        com.jrpetty.mcassistant.Guard.run("the town's own ways", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    second(level, v, tick);
                }
            }
        });
    }

    /** A second of the town's ways: its day worked out once a day, and the rites, the feast and the cook's sign. */
    static void second(ServerLevel level, Villages.Village v, int tick) {
        UUID id = v.id();
        if (Villages.headcount(id) <= 0) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Long last = WORKED.get(id);
        if (last == null || last != day) {
            WORKED.put(id, day);
            daily(level, v, day);
        }
        Beliefs.tick(level, v, day, t);
        TownFeast.tick(level, v, day, t);
        if (tick % 600 == 13) {
            Cuisine.tick(level, v, day, t);
            Architecture.dressTurn(level, v);
        }
    }

    /**
     * The town's ways worked out afresh, once a day: its temper, then what it eats, how it talks, how it builds, what
     * it believes and what it feasts, each from what is true of it today. Nothing is chosen before its land has been
     * looked over (Homeland), or the town is a couple of days old: a town's ways are its land's first.
     */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        long founded = FoundingDay.founded(id);
        if (Homeland.known(id) == null && (founded < 0 || day - founded < 2)) return;
        heart(id, true);
        Cuisine.choose(level, v, day);
        TownSpeech.daily(level, v, day);
        Architecture.choose(level, v, day);
        Beliefs.choose(level, v, day);
        TownFeast.choose(level, v, day);
    }

    /** Tests and /village ways: the town's ways worked out now, whatever the day. */
    public static void workOutForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        WORKED.put(v.id(), day);
        daily(level, v, day);
    }

    // ------------------------------------------------------------------ the town's temper

    /**
     * The town's temper: the value most of its grown folk put first (Values.top), worked out once a day and kept in
     * the ledger (so a town whose folk are not loaded still has one). Its founders' temper is kept too, the first
     * time it is worked out: what the town was founded for.
     */
    public static Values.Value heart(UUID village) {
        Values.Value v = HEART.get(village);
        if (v != null) return v;
        return heart(village, false);
    }

    static Values.Value heart(UUID village, boolean fresh) {
        Values.Value now = null;
        if (fresh || note(village, "heart") == null) {
            int[] count = new int[Values.N];
            int grown = 0;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || !f.life().rolled()) continue;
                count[Values.top(f).ordinal()]++;
                grown++;
            }
            if (grown > 0) {
                int best = 0;
                for (int i = 1; i < Values.N; i++) if (count[i] > count[best]) best = i;
                now = Values.Value.values()[best];
                note(village, "heart", now.name());
                if (note(village, "founders") == null) note(village, "founders", now.name());
            }
        }
        if (now == null) now = valueOf(note(village, "heart"));
        if (now == null) now = Values.Value.FOOD;
        HEART.put(village, now);
        return now;
    }

    /** What the town was founded for: its founders' temper, as first worked out (or its temper now, before that). */
    public static Values.Value founders(UUID village) {
        Values.Value v = valueOf(note(village, "founders"));
        return v != null ? v : heart(village);
    }

    /** Tests: this town's temper, and its founders', set so (as if its folk had come to care about this most). */
    public static void heartForTests(UUID village, Values.Value now, @Nullable Values.Value founders) {
        note(village, "heart", now.name());
        if (founders != null) note(village, "founders", founders.name());
        HEART.put(village, now);
    }

    @Nullable
    static Values.Value valueOf(@Nullable String s) {
        if (s == null) return null;
        try {
            return Values.Value.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The temper in a word: "devout", "martial", "merchant"... */
    public static String temperWord(Values.Value v) {
        return switch (v) {
            case FOOD -> "provident";
            case HOMES -> "homely";
            case PROGRESS -> "learned";
            case SAFETY -> "martial";
            case WEALTH -> "merchant";
            case LEISURE -> "merry";
            case TRADITION -> "devout";
        };
    }

    // ------------------------------------------------------------------ the land in a word

    /** What a town on this land calls itself, before a noun: "a hill-town take on...", "a true harbour-town soul". */
    public static String landWord(Homeland.Land l) {
        return switch (l) {
            case COAST -> "harbour-town";
            case RIVER -> "river-town";
            case FOREST -> "forest";
            case TAIGA -> "pine-wood";
            case SNOW -> "snow-country";
            case MOUNTAIN -> "hill-town";
            case DESERT -> "well-town";
            case SAVANNA -> "herding";
            case JUNGLE -> "jungle";
            case SWAMP -> "fen";
            case BADLANDS -> "mesa-town";
            case MEADOW -> "meadow";
            case PLAINS -> "plains";
        };
    }

    // ------------------------------------------------------------------ colonies

    /**
     * A colony founded (Colonies.found): the settlers take their mother's ways with them. Its dish goes with them, to
     * be made in the colony's own way once its land is known (Cuisine.choose); its tongue they keep a while; and its
     * faith they carry, unless the new land calls them to another (Beliefs.choose).
     */
    public static void colonised(UUID mother, UUID colony, long day) {
        if (mother.equals(colony)) return;
        note(colony, "mother", mother + "|" + Villages.name(mother));
        Cuisine.Dish d = Cuisine.dishOf(mother);
        if (d != null) note(colony, "mother.dish", d.name());
        Beliefs.Belief b = Beliefs.of(mother);
        if (b != null) note(colony, "mother.belief", b.name());
    }

    /** The colony's mother town, if it is one (the town's name), or null. */
    @Nullable
    static String motherName(UUID village) {
        String m = note(village, "mother");
        if (m == null) return null;
        int bar = m.indexOf('|');
        return bar < 0 ? null : m.substring(bar + 1);
    }

    // ------------------------------------------------------------------ the player's side

    /** The house dish's sign at the tavern, right-clicked: one portion bought (Cuisine.order). */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!Cuisine.isHouseSign(level, e.getPos())) return;
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, e.getPos(), 96);
        if (v == null) return;
        Player p = e.getEntity();
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(Cuisine.order(level, v, p)), true);
    }

    /** The folk's card: its nickname, and how much of its town's ways it carries ("a true harbour-town soul"). */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isShowcase()) return null;
        List<String> bits = new ArrayList<>();
        String nick = TownSpeech.nickname(f);
        if (nick != null) bits.add("known as " + nick);
        Homeland.Land l = Homeland.known(id);
        Values.Value heart = heart(id);
        if (l != null && f.life().rolled() && !f.isBaby() && Values.top(f) == heart) {
            bits.add("a true " + landWord(l) + " soul, " + temperWord(heart) + " as its town");
        }
        String rite = Beliefs.cardLine(f);
        if (rite != null) bits.add(rite);
        String ate = Cuisine.cardLine(f);
        if (ate != null) bits.add(ate);
        if (bits.isEmpty()) return null;
        String s = String.join("; ", bits);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * The board's line for the town's ways, the summary a passer-by reads: "Coastal houses; fish stew and game pie;
     * the Herring Fair; we keep faith with the Sea".
     */
    @Nullable
    public static String boardLine(UUID village) {
        String s = summary(village);
        return s == null ? null : "FN|Our ways: " + s + ".";
    }

    /**
     * The town's ways in a few words, for a summary line (the board, Group I's Identity summary): "Coastal houses; its
     * fish stew; the Herring Fair; faith in the Sea". Null before any of it is known.
     */
    @Nullable
    public static String summary(UUID village) {
        List<String> parts = new ArrayList<>();
        Architecture.Style st = Architecture.of(village);
        if (st != null) parts.add(st.words + " houses");
        Cuisine.Dish d = Cuisine.dishOf(village);
        if (d != null) parts.add("famous for its " + d.words);
        TownFeast.Feast f = TownFeast.of(village);
        if (f != null) parts.add(f.words);
        Beliefs.Belief b = Beliefs.of(village);
        if (b != null) parts.add("faith in " + b.words);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * The town's ways as lines, for a page: its table, its tongue, its building, its feast and its faith. (Group I's
     * Identity page takes these, if it is there to take them; the Culture page shows them meanwhile.)
     */
    public static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        Homeland.Land l = Homeland.known(village);
        Values.Value heart = heart(village), founders = founders(village);
        out.add(Villages.name(village) + " is " + (l == null ? "a town" : "a " + landWord(l) + " town") + ", " + temperWord(heart)
            + " of temper" + (founders != heart ? " (its founders were " + temperWord(founders) + ")" : "")
            + (motherName(village) == null ? "" : "; settled from " + motherName(village)) + ".");
        out.addAll(Cuisine.lines(village));
        out.addAll(TownSpeech.lines(village));
        out.addAll(Architecture.lines(village));
        out.addAll(TownFeast.lines(village));
        out.addAll(Beliefs.lines(village));
        return out;
    }

    /** The Culture page's sections (CulturePage): the table, the tongue, the building, the feast and the faith. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> head = new ArrayList<>();
        head.add(lines(id).get(0));
        out.put("ways", section(head));
        out.put("table", section(Cuisine.lines(id)));
        out.put("tongue", section(TownSpeech.lines(id)));
        out.put("building", section(Architecture.lines(id)));
        out.put("feast", section(TownFeast.lines(id)));
        out.put("faith", section(Beliefs.lines(id)));
        return out;
    }

    private static CompoundTag section(List<String> lines) {
        CompoundTag t = new CompoundTag();
        t.put("lines", Culture.strings(lines));
        return t;
    }

    /** /village ways: all of it in chat. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        out.add("The ways of " + Villages.name(v.id()) + ":");
        for (String s : lines(v.id())) out.add("  " + s);
        return out;
    }

    // ------------------------------------------------------------------ talk

    /**
     * "What's this town like?", "What do you eat here?", "What do you believe?", "Any sayings?", "What do they call
     * you?": a folk answers of its own town's ways. Null if the words ask about none of it.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        UUID id = f.ownerId();
        if (id == null || text == null || text.isBlank()) return null;
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ") + " ";
        boolean town = has(t, "town like", "village like", "place like", "this town", "your town like", "your ways", "your customs",
            "tell me about the town", "tell me about your town", "what's it like here", "whats it like here");
        if (has(t, "what do you eat", "local dish", "your dish", "food here", "eat here", "your food", "what's good to eat",
                "whats good to eat", "house dish", "speciality", "specialty", "delicacy")) {
            return Cuisine.talk(f);
        }
        if (has(t, "believe", "faith", "worship", "sacred", "pray", "your god", "rites", "religion", "taboo")) return Beliefs.talk(f);
        if (has(t, "saying", "sayings", "proverb", "what do you say", "expression")) return TownSpeech.talkSayings(f);
        if (has(t, "nickname", "call you", "what do they call", "known as")) return TownSpeech.talkNick(f);
        if (has(t, "festival", "your feast", "celebrate", "holiday")) return TownFeast.talk(f);
        if (has(t, "houses look", "your houses", "build like", "building style", "architecture", "how you build")) return Architecture.talk(f);
        if (!town) return null;
        List<String> bits = new ArrayList<>();
        Homeland.Land l = Homeland.known(id);
        String name = Villages.name(id);
        bits.add(l == null ? name + "? It's home." : name + "'s a " + landWord(l) + " town, " + temperWord(heart(id)) + " to the bone.");
        Cuisine.Dish d = Cuisine.dishOf(id);
        if (d != null) bits.add("You've not lived till you've had our " + d.words + ".");
        Architecture.Style st = Architecture.of(id);
        if (st != null) bits.add(st.boast);
        TownFeast.Feast fe = TownFeast.of(id);
        if (fe != null) bits.add("Come for " + fe.words + ", if you can.");
        Beliefs.Belief b = Beliefs.of(id);
        if (b != null) bits.add(b.creed);
        String saying = TownSpeech.saying(id, f.getRandom());
        if (saying != null) bits.add("As we say here: \"" + saying + "\".");
        return String.join(" ", bits);
    }

    static boolean has(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /** "Ashhaven" in a possessive: "Ashhaven's". */
    static String of(String name) {
        return name.endsWith("s") ? name + "'" : name + "'s";
    }

    /** The first letter up. */
    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** How many grown folk of the town are at each trade. */
    static Map<AssistantEntity.StationTask, Integer> trades(UUID village) {
        Map<AssistantEntity.StationTask, Integer> out = new EnumMap<>(AssistantEntity.StationTask.class);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) out.merge(f.stationTask(), 1, Integer::sum);
        }
        return out;
    }
}
