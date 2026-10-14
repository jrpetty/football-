package com.jrpetty.mcassistant.village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [admin] What the admin toolkit can do, as data: every action's area, its words, what it does in one line, an
 * example, its parameters and whether it is destructive or big. The one list the command tree, the help, the tab
 * completion, the panel and the scripts are all built from, so they can never disagree.
 *
 * <p>Plain Java (no game classes): the panel reads it on the client and the unit tests load it without Minecraft.
 * What each action does is bound on the server (admin/AdminActions).
 */
public final class AdminSpec {

    private AdminSpec() {}

    /** The areas, each with its own permission node and the panel's left nav. Nouns, as tabs are. */
    public enum Area {
        TOWN("Town", "inspect, snapshots, presets and the log"),
        AGES("Ages", "ages and research"),
        PEOPLE("People", "population, folk, families and leaders"),
        ARMY("Army & War", "kit, armies, wars, vassals and raids"),
        ECONOMY("Economy", "treasury, coin, stores, prices and trade"),
        BUILD("Build", "buildings, walls, renovation and builders"),
        EVENTS("Events", "festivals, disasters, visitors and envoys"),
        WORLD("World", "time, weather, new towns, scenarios and scripts"),
        DEBUG("Debug", "overlays, the town audit and tick cost");

        public final String title, blurb;

        Area(String title, String blurb) {
            this.title = title;
            this.blurb = blurb;
        }

        /** The area's word in commands and config ("army"). */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Area byKey(String key) {
            for (Area a : values()) if (a.key().equalsIgnoreCase(key)) return a;
            return null;
        }
    }

    /** What a parameter takes: it decides the tab completion and the panel's field. */
    public enum Kind { INT, WORD, TOWN, FOLK, TRADE, AGE, TIER, ITEM, PHASE, SCENARIO, CIVIC, STRUCTURE, EVENT, PLAYER, FILE, TEXT }

    /**
     * One parameter: its name, its kind, whether it may be left out, its range (INT) or its choices (WORD), and the
     * value the panel offers first.
     */
    public record Param(String name, Kind kind, boolean optional, int min, int max, List<String> choices, String fallback) {

        public static Param num(String name, int min, int max, int fallback) {
            return new Param(name, Kind.INT, false, min, max, List.of(), Integer.toString(fallback));
        }

        public static Param of(String name, Kind kind) {
            return new Param(name, kind, false, 0, 0, List.of(), "");
        }

        public static Param words(String name, String... choices) {
            return new Param(name, Kind.WORD, false, 0, 0, List.of(choices), choices.length == 0 ? "" : choices[0]);
        }

        public Param opt() {
            return new Param(name, kind, true, min, max, choices, fallback);
        }

        public Param or(String value) {
            return new Param(name, kind, optional, min, max, choices, value);
        }

        /** "<count 1-500>", "[age]". */
        public String usage() {
            String inner = kind == Kind.INT ? name + " " + min + "-" + max
                : kind == Kind.WORD && !choices.isEmpty() ? String.join("|", choices) : name;
            return optional ? "[" + inner + "]" : "<" + inner + ">";
        }
    }

    /** Where an action acts: on towns (by the town: selector), on folk (by folk:), or on the world/the toolkit itself. */
    public enum Scope { TOWN, FOLK, WORLD }

    /**
     * One action. {@code path} is what follows /village admin ("ages set"); destructive actions ask for a confirm
     * token (or a higher level); big ones run over ticks as a job and suggest a backup first.
     */
    public record Action(String id, Area area, String path, Scope scope, String summary, String example,
                         List<Param> params, boolean destructive, boolean big) {

        /** "/village admin ages set <age> [instant|queued]". */
        public String usage() {
            StringBuilder sb = new StringBuilder("/village admin ").append(path);
            for (Param p : params) sb.append(' ').append(p.usage());
            return sb.toString();
        }

        @Nullable
        public Param param(int i) {
            return i >= 0 && i < params.size() ? params.get(i) : null;
        }
    }

    /** The flags every action understands. */
    public static final String DRY_RUN = "--dry-run", SNAPSHOT = "--snapshot", PAID = "--paid";

    /** Hard caps, for safety (an admin's typo should never stand up fifty thousand folk). */
    public static final int MOST_FOLK = 500, MOST_DAYS = 60, MOST_COIN = 1_000_000, MOST_RAID = 40, MOST_ARMY = 200;

    public static final List<String> AGES = List.of("wood", "stone", "iron", "diamond", "nether");
    public static final List<String> TIERS = List.of("leather", "chain", "iron", "diamond", "netherite");
    public static final List<String> PHASES = List.of("muster", "march", "lineup", "parley", "horn", "volley", "charge", "clash", "rout", "aftermath",
        "surrender", "peace");                                                  // [war-polish] the horn and the charge too
    public static final List<String> SCENARIOS = List.of("battle", "market", "restaurant", "raid", "big-city", "earning");
    public static final List<String> EVENTS = List.of("festival", "wedding", "birth", "funeral", "election", "fire", "flood", "drought",
        "illness", "envoy", "feud", "caravan", "visitor", "refugees");
    public static final List<String> OVERLAYS = List.of("lots", "wall", "stuck", "ore", "chests", "wanted", "off");

    private static final Map<String, Action> BY_ID = new LinkedHashMap<>();
    private static final List<Action> ALL = new ArrayList<>();

    private static void add(String id, Area area, String path, Scope scope, String summary, String example, Param... params) {
        put(new Action(id, area, path, scope, summary, example, List.of(params), false, false));
    }

    private static void danger(String id, Area area, String path, Scope scope, String summary, String example, Param... params) {
        put(new Action(id, area, path, scope, summary, example, List.of(params), true, false));
    }

    private static void big(String id, Area area, String path, Scope scope, String summary, String example, Param... params) {
        put(new Action(id, area, path, scope, summary, example, List.of(params), false, true));
    }

    private static void put(Action a) {
        if (BY_ID.put(a.id(), a) != null) throw new IllegalStateException("two admin actions called " + a.id());
        ALL.add(a);
    }

    static {
        Scope T = Scope.TOWN, F = Scope.FOLK, W = Scope.WORLD;
        // ---- town: look before you leap, keep a copy, set up again
        add("inspect.town", Area.TOWN, "inspect town", T, "The town at a glance: age and progress, trades, homes, stores, treasury, wars, cost, problems.",
            "inspect town town:Oakford");
        add("inspect.folk", Area.TOWN, "inspect folk", F, "One folk: trade and level, age, needs, health, home, partner.", "inspect folk folk:Ada");
        add("inspect.building", Area.TOWN, "inspect building", T, "The building you stand in or nearest: design, age, stamp, residents.", "inspect building");
        add("snapshot", Area.TOWN, "snapshot", T, "Copy the town's saved data (age, treasury, notes, research) to a file. Not blocks.", "snapshot");
        danger("restore", Area.TOWN, "restore", T, "Put a snapshot's saved data back (age, treasury, notes). Blocks and folk are not touched.",
            "restore oakford-3", Param.of("snapshot", Kind.FILE));
        add("preset.save", Area.TOWN, "preset save", T, "Save this town's setup (age, population, kit, stores) as a named preset.",
            "preset save iron-70", Param.of("name", Kind.WORD));
        big("preset.apply", Area.TOWN, "preset apply", T, "Bring a town up to a preset: its age, headcount, kit and stores.",
            "preset apply iron-70 town:Ashby", Param.of("name", Kind.FILE));
        add("preset.list", Area.TOWN, "preset list", W, "The saved presets.", "preset list");
        // ---- ages and research
        big("ages.set", Area.AGES, "ages set", T, "Set the age: its trades, research and rules open; buildings renovated; stores and tools stocked.",
            "ages set iron instant", Param.of("age", Kind.AGE), Param.words("renovate", "instant", "queued", "none").opt(),
            Param.words("fill", "fill", "nofill").opt());
        big("ages.next", Area.AGES, "ages next", T, "Advance one age, as ages set does.", "ages next");
        add("ages.needs", Area.AGES, "ages needs", T, "What the town still needs before its next age.", "ages needs");
        big("ages.meet", Area.AGES, "ages meet", T, "Meet the next age's needs: the goods put in the stores, the buildings put up.", "ages meet");
        add("research.unlock", Area.AGES, "research unlock", T, "Complete one civic now.", "research unlock common_tools", Param.of("civic", Kind.CIVIC));
        big("research.all", Area.AGES, "research all", T, "Complete every civic the town can (rivals stay closed).", "research all");
        big("research.knacks", Area.AGES, "research knacks", F, "Give folk every knack open to them.", "research knacks folk:@all");
        // ---- people
        big("people.add", Area.PEOPLE, "people add", T, "Add folk: a spread of trades and ages, couples, beds found, houses queued.",
            "people add 50", Param.num("count", 1, MOST_FOLK, 10));
        big("people.trade", Area.PEOPLE, "people trade", T, "Add folk of one trade.", "people trade smith 3", Param.of("trade", Kind.TRADE),
            Param.num("count", 1, MOST_FOLK, 1));
        big("people.children", Area.PEOPLE, "people children", T, "Add children to the town's couples (or as orphans taken in).", "people children 5",
            Param.num("count", 1, 100, 3));
        big("people.elders", Area.PEOPLE, "people elders", T, "Add elders, past sixty.", "people elders 3", Param.num("count", 1, 100, 3));
        big("people.couples", Area.PEOPLE, "people couples", T, "Add married couples (two folk each).", "people couples 4", Param.num("count", 1, 100, 2));
        big("people.charter", Area.PEOPLE, "people charter", T, "Bring the headcount up to a number (the server's charter size if none).",
            "people charter 70", Param.num("count", 1, MOST_FOLK, 70).opt());
        danger("people.remove", Area.PEOPLE, "people remove", T, "Send N folk away (the ones with least to keep them).", "people remove 5",
            Param.num("count", 1, MOST_FOLK, 1));
        danger("people.kill", Area.PEOPLE, "people kill", F, "Kill folk (graves, mourning and all).", "people kill folk:@all");
        add("people.heal", Area.PEOPLE, "people heal", F, "Heal folk: full health, colds and wounds gone.", "people heal folk:@all");
        add("people.skill", Area.PEOPLE, "people skill", F, "Set folk's level at their trade.", "people skill 10 folk:@smith",
            Param.num("level", 0, 50, 10));
        add("people.needs", Area.PEOPLE, "people needs", F, "Fill needs, mood and health: fed, rested, in company.", "people needs folk:@all");
        add("people.grow", Area.PEOPLE, "people grow", F, "Grow children up now.", "people grow folk:@all");
        add("people.marry", Area.PEOPLE, "people marry", T, "Pledge single folk to each other; the town holds the weddings.", "people marry 2",
            Param.num("couples", 1, 50, 1));
        add("people.births", Area.PEOPLE, "people births", T, "A child born to each of so many couples.", "people births 2", Param.num("births", 1, 50, 1));
        add("people.leader", Area.PEOPLE, "people leader", F, "Make a folk the town's leader.", "people leader folk:Ada");
        add("people.election", Area.PEOPLE, "people election", T, "Call an election, the vote today.", "people election");
        // ---- army and war
        add("army.kit", Area.ARMY, "army kit", T, "Arm the watch at a tier: from the stores first, a decree supply for the rest.",
            "army kit iron", Param.of("tier", Kind.TIER), Param.words("source", "stores", "decree").opt());
        big("army.raise", Area.ARMY, "army raise", T, "Put N grown folk on the watch (raising folk if short) and kit them.", "army raise 20 iron",
            Param.num("count", 1, MOST_ARMY, 12), Param.of("tier", Kind.TIER).opt().or("iron"));
        add("army.war", Area.ARMY, "army war", T, "Declare war on another town.", "army war Ashby", Param.of("enemy", Kind.TOWN));
        add("army.phase", Area.ARMY, "army phase", T, "Jump a battle with its enemy to a phase (or surrender, peace).", "army phase lineup",
            Param.of("phase", Kind.PHASE));
        add("army.ranged", Area.ARMY, "army ranged", T, "What the army's missile men carry: bows, crossbows, or a mix.", "army ranged crossbows",
            Param.words("arm", "bows", "crossbows", "mix"));
        add("army.vassal", Area.ARMY, "army vassal", T, "Make the town a vassal of another.", "army vassal Ashby", Param.of("overlord", Kind.TOWN));
        add("army.free", Area.ARMY, "army free", T, "Free a vassal town.", "army free");
        add("army.raid", Area.ARMY, "army raid", T, "A raiding band of N at the gate now.", "army raid 8", Param.num("size", 1, MOST_RAID, 6));
        add("army.peace", Area.ARMY, "army peace", T, "End all fighting: peace with every enemy, the raid called off, the battle struck.", "army peace");
        // ---- economy
        add("economy.coin", Area.ECONOMY, "economy coin", T, "Add (or with a minus, take) treasury coin.", "economy coin 500",
            Param.num("amount", -MOST_COIN, MOST_COIN, 100));
        add("economy.pay", Area.ECONOMY, "economy pay", T, "Give a player the town's coin.", "economy pay Steve 64", Param.of("player", Kind.PLAYER),
            Param.num("amount", 1, 6400, 64));
        add("economy.fill", Area.ECONOMY, "economy fill", T, "Fill the stores: all, a category, or the age's starter kit.", "economy fill food",
            Param.words("what", "age", "all", "food", "wood", "stone", "metal", "tools", "cloth").opt());
        add("economy.give", Area.ECONOMY, "economy give", T, "Put so many of an item in the stores.", "economy give iron_ingot 64",
            Param.of("item", Kind.ITEM), Param.num("count", 1, 6400, 64));
        danger("economy.empty", Area.ECONOMY, "economy empty", T, "Empty the town's store chests.", "economy empty");
        add("economy.price", Area.ECONOMY, "economy price", T, "Price shock: an item at so many percent of its usual price (glut < 100 < shortage).",
            "economy price wheat 40", Param.of("item", Kind.ITEM), Param.num("percent", 10, 1000, 100));
        add("economy.wagon", Area.ECONOMY, "economy wagon", T, "A trade shipment of the town's surplus sent to its nearest neighbour now.", "economy wagon");
        add("economy.market", Area.ECONOMY, "economy market", T, "Make today market day (the traders come, the bell rings).", "economy market");
        add("economy.till", Area.ECONOMY, "economy till", T, "Reset the shops' tills and restock their shelves from the stores.", "economy till");
        // ---- build
        add("build.finish", Area.BUILD, "build finish", T, "Finish the buildings under way now.", "build finish", Param.words("cost", "free", "paid").opt());
        big("build.all", Area.BUILD, "build all", T, "Build everything the town wants, one after another.", "build all",
            Param.words("cost", "free", "paid").opt());
        add("build.named", Area.BUILD, "build named", T, "Put one building up now.", "build named market", Param.of("structure", Kind.STRUCTURE),
            Param.words("cost", "free", "paid").opt());
        add("build.walls", Area.BUILD, "build walls", T, "Build the town wall and gates now.", "build walls");
        big("build.renovate", Area.BUILD, "build renovate", T, "Renovate every building to the town's current design and age.", "build renovate");
        add("build.unstick", Area.BUILD, "build unstick", T, "Send stuck builders back to the square and reset their crews.", "build unstick");
        add("build.place", Area.BUILD, "build place", T, "Put a building up where you are looking (it joins the town's register).",
            "build place house", Param.of("structure", Kind.STRUCTURE));
        // ---- events
        add("events.now", Area.EVENTS, "events now", T, "Bring an event about now.", "events now festival", Param.of("event", Kind.EVENT),
            Param.of("detail", Kind.WORD).opt());
        // ---- time and world
        big("world.skip", Area.WORLD, "world skip", W, "Skip N days: every town's morning round run for each day, a day a second.",
            "world skip 5", Param.num("days", 1, MOST_DAYS, 1));
        add("world.season", Area.WORLD, "world season", T, "Turn the town's calendar to a season.", "world season winter",
            Param.words("season", "spring", "summer", "autumn", "winter"));
        add("world.weather", Area.WORLD, "world weather", W, "Set the weather.", "world weather rain", Param.words("weather", "clear", "rain", "thunder"));
        add("world.time", Area.WORLD, "world time", W, "Set the time of day.", "world time dusk", Param.words("time", "dawn", "noon", "dusk", "midnight"));
        big("world.found", Area.WORLD, "world found", W, "Found a new town here with N folk, at an age.", "world found 70 iron",
            Param.num("folk", 2, MOST_FOLK, 20), Param.of("age", Kind.AGE).opt().or("wood"));
        big("world.rival", Area.WORLD, "world rival", T, "Found a rival town so far off.", "world rival 260 30",
            Param.num("distance", 200, 2000, 260), Param.num("folk", 2, MOST_FOLK, 20).opt());
        add("world.tp", Area.WORLD, "world tp", W, "Teleport to a town, a folk or a building.", "world tp Oakford", Param.of("where", Kind.TEXT));
        big("scenario", Area.WORLD, "scenario", W, "Build a ready-made scene to test a feature.", "scenario battle", Param.of("name", Kind.SCENARIO));
        big("run", Area.WORLD, "run", W, "Run a script of admin commands from <world>/mcassistant/admin-scripts.", "run iron-age-war",
            Param.of("file", Kind.FILE));
        add("scripts", Area.WORLD, "scripts", W, "The scripts there are to run.", "scripts");
        // ---- debug
        add("debug.overlay", Area.DEBUG, "debug overlay", T, "Show an overlay to you for a minute: lots, the wall ring, stuck spots, ore notes, "
            + "work chests, the Wanted/needs view.", "debug overlay lots", Param.words("view", OVERLAYS.toArray(new String[0])));
        add("debug.audit", Area.DEBUG, "debug audit", T, "The town audit report.", "debug audit");
        add("debug.cost", Area.DEBUG, "debug cost", W, "Server tick time and each town's measured cost.", "debug cost");
    }

    /** Every action, in the order the help and the panel list them. */
    public static List<Action> all() {
        return Collections.unmodifiableList(ALL);
    }

    @Nullable
    public static Action byId(String id) {
        return BY_ID.get(id);
    }

    /** The action whose path is these words ("ages set"), or null. */
    @Nullable
    public static Action byPath(String path) {
        String p = path.trim().toLowerCase(Locale.ROOT);
        for (Action a : ALL) if (a.path().equals(p)) return a;
        return null;
    }

    /** An area's actions. */
    public static List<Action> of(Area area) {
        List<Action> out = new ArrayList<>();
        for (Action a : ALL) if (a.area() == area) out.add(a);
        return out;
    }

    /** Actions whose words or summary hold every word of the query (the panel's search box). */
    public static List<Action> search(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Action> out = new ArrayList<>();
        for (Action a : ALL) {
            String hay = (a.path() + " " + a.summary() + " " + a.area().title).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all) out.add(a);
        }
        return out;
    }
}
