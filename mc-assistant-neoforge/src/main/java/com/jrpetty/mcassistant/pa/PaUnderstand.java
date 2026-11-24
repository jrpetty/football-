package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaIntent.Kind;
import com.jrpetty.mcassistant.pa.PaIntent.Place;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa33] How the Player AI Assistant reads what it is told. Offline and deterministic: the same words always read
 * the same way. A sentence is made plain ({@link PaWords}), cut into steps ("chop 64 logs, then make planks and
 * sticks, then build a crafting table by my house" is four), and each step read as one {@link PaIntent}. A step it
 * cannot read is never dropped in silence: the reading carries a short question back, with choices to click.
 *
 * <p>It reads everything the old chat parser (ChatControl) read, and everything a folk can do; PaOrders hands a step
 * it does not know to ChatControl's own reader before it asks back. Pure Java, unit-tested with a few hundred phrasings.
 */
public final class PaUnderstand {

    private PaUnderstand() {}

    /** One clickable answer to a question the assistant asks back: what the button says, and what clicking it says. */
    public record Choice(String label, String say) {}

    /** A question asked back, with its choices. */
    public record Clarify(String question, List<Choice> choices) {}

    /** What a sentence was read as: its steps in order, and a question when some of it could not be read. */
    public record Reading(List<PaIntent> steps, Clarify clarify) {
        public boolean understood() {
            return clarify == null && !steps.isEmpty();
        }

        /** "GATHER:logs:64 > CRAFT:planks" for the tests and the logs. */
        public String brief() {
            List<String> b = new ArrayList<>();
            for (PaIntent i : steps) b.add(i.brief());
            return String.join(" > ", b) + (clarify != null ? " ?" : "");
        }
    }

    // ------------------------------------------------------------------ vocabulary

    /** Raw stuff the assistant gathers from the world (word -> what is said back). */
    static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    static {
        String[][] r = {
            {"logs", "logs"}, {"cobblestone", "stone"}, {"stone", "stone"}, {"dirt", "dirt"}, {"sand", "sand"},
            {"gravel", "gravel"}, {"clay", "clay"}, {"iron", "iron"}, {"coal", "coal"}, {"copper", "copper"},
            {"gold", "gold"}, {"diamond", "diamond"}, {"redstone", "redstone"}, {"lapis", "lapis"},
            {"emerald", "emerald"}, {"obsidian", "obsidian"}, {"cane", "sugar cane"}, {"wheat", "wheat"},
            {"carrots", "carrots"}, {"potatoes", "potatoes"}, {"pumpkin", "pumpkin"}, {"melon", "melon"},
            {"seeds", "seeds"}, {"wool", "wool"}, {"leather", "leather"}, {"string", "string"}, {"food", "food"},
            {"flowers", "flowers"}, {"saplings", "saplings"},
        };
        for (String[] p : r) RESOURCES.put(p[0], p[1]);
    }

    /** The ores: a "mine" order for one of these digs for it. */
    static final Set<String> ORES = Set.of("iron", "coal", "copper", "gold", "diamond", "redstone", "lapis", "emerald");

    /** Crops: "harvest the wheat" is farming. */
    static final Set<String> CROPS = Set.of("wheat", "carrots", "potatoes", "beetroot", "crops", "pumpkin", "melon");

    /** Things made at a table or a furnace. */
    static final List<String> TOOLS = List.of("pickaxe", "axe", "sword", "shovel", "hoe");
    static final List<String> MATERIALS = List.of("wooden", "stone", "iron", "golden", "diamond", "netherite");
    static final List<String[]> CRAFTABLES = List.of(
        new String[]{"crafting table", "crafting table"}, new String[]{"workbench", "crafting table"},
        new String[]{"furnace", "furnace"}, new String[]{"chest", "chest"}, new String[]{"barrel", "barrel"},
        new String[]{"planks", "planks"}, new String[]{"sticks", "sticks"}, new String[]{"stick", "sticks"},
        new String[]{"torches", "torches"}, new String[]{"bed", "bed"}, new String[]{"door", "door"},
        new String[]{"ladder", "ladder"}, new String[]{"fence", "fence"}, new String[]{"boat", "boat"},
        new String[]{"bucket", "bucket"}, new String[]{"shears", "shears"}, new String[]{"bow", "bow"},
        new String[]{"arrows", "arrows"}, new String[]{"shield", "shield"}, new String[]{"helmet", "helmet"},
        new String[]{"chestplate", "chestplate"}, new String[]{"leggings", "leggings"}, new String[]{"boots", "boots"},
        new String[]{"armor", "armor"}, new String[]{"armour", "armor"}, new String[]{"bread", "bread"},
        new String[]{"glass", "glass"}, new String[]{"charcoal", "charcoal"}, new String[]{"ingots", "ingots"},
        new String[]{"fishing rod", "fishing rod"}, new String[]{"rod", "fishing rod"}, new String[]{"lantern", "lantern"},
        new String[]{"campfire", "campfire"}, new String[]{"paper", "paper"}, new String[]{"book", "book"},
        new String[]{"cake", "cake"}, new String[]{"compass", "compass"}, new String[]{"map", "map"},
        new String[]{"hopper", "hopper"}, new String[]{"piston", "piston"}, new String[]{"rail", "rail"},
        new String[]{"slab", "slab"}, new String[]{"stairs", "stairs"}, new String[]{"wall", "wall"});

    /** Buildings it can raise from the mod's drawings (word -> drawing). */
    static final Map<String, String> BUILDINGS = new LinkedHashMap<>();
    static {
        String[][] b = {
            {"house", "house"}, {"cottage", "house"}, {"cabin", "house"}, {"home", "house"}, {"hut", "shelter"},
            {"shelter", "shelter"}, {"smeltery", "smeltery"}, {"forge", "smeltery"}, {"storage", "storage"},
            {"warehouse", "storage"}, {"storehouse", "storehouse"}, {"workshop", "workshop"},
            {"watchtower", "watchtower"}, {"tower", "watchtower"}, {"lighthouse", "lighthouse"}, {"well", "well"},
            {"pen", "pen"}, {"barn", "pen"}, {"enclosure", "pen"}, {"wall", "wall"}, {"platform", "platform"},
            {"bridge", "bridge"}, {"farmhouse", "house2"}, {"chapel", "chapel"}, {"granary", "granary"},
            {"smithy", "smithy"}, {"shop", "shop"}, {"library", "library"}, {"tavern", "tavern"}, {"inn", "tavern"},
            {"fortify", "fortify"}, {"fort", "fortify"}, {"room", "room"}, {"column", "column"}, {"pillar", "column"},
        };
        for (String[] p : b) BUILDINGS.put(p[0], p[1]);
    }

    static final Set<String> MONSTERS = Set.of("monster", "monsters", "zombie", "skeleton", "spider", "creeper",
        "enderman", "witch", "pillager", "slime", "phantom", "drowned", "husk", "stray");
    static final Set<String> ANIMALS = Set.of("cows", "cow", "pigs", "pig", "sheep", "chickens", "chicken", "rabbits",
        "rabbit", "animals", "goats", "horses");

    private static final Pattern NUM = Pattern.compile("\\b(\\d{1,4})\\b");
    private static final Pattern STACKS = Pattern.compile("\\b(\\d{1,2}) stacks?\\b");
    private static final Pattern A_STACK = Pattern.compile("\\b(?:a|one) stack\\b");
    private static final Pattern LEVEL = Pattern.compile("\\b(?:level|y|y level|layer|depth) ?(-?\\d{1,3})\\b");
    private static final Pattern AREA = Pattern.compile("\\b(\\d{1,2}) ?(?:x|by) ?(\\d{1,2})\\b");
    private static final Pattern RENAME = Pattern.compile("^(?:your name is|you are called|call yourself|i will call you|i ll call you|ill call you|rename yourself(?: to)?|rename to|be called|name yourself) ([a-z0-9_]{2,16})$");

    /** Lead-ins that carry no order of their own: "can you", "i want you to", "go and"... */
    private static final Pattern LEAD = Pattern.compile("^(?:(?:hello|ok|so|now|also|just|then|and|right|alright|quickly|quick|real quick|please|assistant|buddy|mate|pal|friend)\\s+)*"
        + "(?:(?:can|could|would|will) you (?:please )?|i (?:want|need|would like) you to |i want to |i need to |you should |you need to |you can |"
        + "(?:go|run) and |go |try to |time to |let us |i need |i want |i would like |make sure to |be a dear and )?");

    /** The order's verb families. */
    private static final Set<String> GATHER_V = Set.of("gather", "collect", "get", "grab", "fetch", "chop", "cut",
        "harvest", "obtain", "acquire", "find", "bring", "pick", "punch", "farm");

    // ------------------------------------------------------------------ reading

    /** Read a sentence. {@code name} is the assistant's own name, stripped when the player addresses it. */
    public static Reading read(String text, String name) {
        String norm = PaWords.normalise(text);
        if (name != null && !name.isEmpty()) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            norm = norm.replaceAll("^(?:hello |ok )?" + Pattern.quote(n) + "\\b ?,? ?", "").trim();
        }
        norm = norm.replaceAll("^(?:hello |ok )?(?:assistant|buddy|mate|robot|bot)\\b ?,? ?", "").trim();
        norm = norm.replaceAll("^, ?", "").replaceAll(" ?,$", "").trim();
        if (norm.isEmpty() || norm.equals(",")) {
            return new Reading(List.of(), new Clarify("Yes? Tell me what you need.", starters()));
        }
        norm = (" " + norm + " ").replaceAll(" (?:go|come|run|hurry) and ", " ").replaceAll(" (?:would|do) you mind ", " ").trim();
        String flat = norm.replace(" , ", " ").replace(",", "").trim();
        List<PaIntent> logi = PaLogiParse.read(stripName(text, name));   // [pa36] chests, gathering into them, stock jobs, counts, hauls
        if (logi != null) return new Reading(logi, null);
        // [pa33c-2b] pings and places, read whole (they look like questions or lead-ins otherwise)
        PaIntent place0 = places(flat, text);
        if (place0 != null) return new Reading(List.of(place0), null);
        PaIntent skill0 = skills(flat, text);                          // [pa33d] the rest of the game, read whole
        if (skill0 != null) return new Reading(List.of(skill0), null);
        // [pa33c-2b] its bed, read whole ("that's your bed" would lose its lead-in; "where's your bed" is an order, not a question)
        if (flat.matches("^(?:this|that)(?:'s|s| is| one is| one's)? (?:your|ur) (?:bed|spawn)(?: now)?$")) return new Reading(List.of(PaIntent.of(Kind.BED_USE, text).withPlace(Place.LOOKED)), null);
        if (flat.matches("^(?:where(?:'s|s| is| are)? (?:is )?(?:your|ur) (?:bed|spawn)|where do you sleep|which bed is yours|have you got a bed|do you have a bed)(?: .*)?$"))
            return new Reading(List.of(PaIntent.of(Kind.BED_WHERE, text)), null);
        // A question is read whole: it is not cut into steps.
        PaIntent q = question(flat, text);
        if (q != null) return new Reading(List.of(q), null);
        PaIntent small = smallTalk(flat, text);
        if (small != null) return new Reading(List.of(small), null);

        List<PaIntent> steps = new ArrayList<>();
        PaIntent prev = null;
        for (String clause : split(norm)) {
            String c0 = clause.trim().replaceAll("^(?:and|then|also|after that) ", "").trim();
            // [pa33c-2b] "go there" before the lead-ins are cut ("go " is one); "..., and that" is the last order again, at the next look
            PaIntent at = c0.isEmpty() ? null : places(c0, text);
            if (at != null && at.kind() != Kind.FIND) { steps.add(at); prev = at; continue; }
            if (prev != null && prev.place() == Place.LOOKED && c0.matches("^(?:and )?(?:that|this|there|it|that 1|this 1|that too|this too|and that)(?: too| as well)?$")) {
                PaIntent again = new PaIntent(prev.kind(), prev.item(), prev.amount(), Place.LOOKED, text);
                steps.add(again);
                prev = again;
                continue;
            }
            String c = LEAD.matcher(clause.trim()).replaceFirst("").trim();
            c = c.replaceAll("^(?:and|then|also|after that) ", "").trim();
            if (c.isEmpty()) continue;
            PaIntent i = clause(c, prev, text);
            if (i.kind() == Kind.UNKNOWN) {
                return new Reading(steps, clarifyFor(c, i));
            }
            steps.add(i);
            prev = i;
        }
        if (steps.isEmpty()) return new Reading(List.of(), notSure(flat));
        return new Reading(steps, null);
    }

    /** [pa36] The sentence as said, without its leading "Basil," / "assistant," (the logistics reader makes its own plain words). */
    private static String stripName(String text, String name) {
        String t = text.trim();
        if (name != null && !name.isEmpty()) t = t.replaceAll("(?i)^(?:hello |hey |ok |oi )?" + Pattern.quote(name.replace('_', ' ')) + "\\b\\s*,?\\s*", "");
        return t.replaceAll("(?i)^(?:hello |hey |ok |oi )?(?:assistant|buddy|robot|bot)\\b\\s*,?\\s*", "");
    }

    /** [pa33c-2b] Places and pings: go there, go back to where I died, go to my bed, the mine; remember this as...; find the nearest...;
     *  ping/mark that; cancel or clear pings, skip one, do one first. Null when it's none of these (or more than one order). */
    static PaIntent places(String c, String raw) {
        if (c.matches("^(?:cancel|remove|delete|forget|drop) (?:that|this|the|my) (?:last )?ping$")) return new PaIntent(Kind.PING_EDIT, "cancel", 0, Place.NONE, raw);
        if (c.matches("^(?:clear|remove|delete|cancel) (?:all )?(?:the |my |those )?pings$|^clear all$")) return new PaIntent(Kind.PING_EDIT, "clear", 0, Place.NONE, raw);
        if (c.matches("^skip (?:that|this|the) (?:one|1|job|ping|step)$|^skip it$|^skip that$")) return new PaIntent(Kind.PING_EDIT, "skip", 0, Place.NONE, raw);
        java.util.regex.Matcher first = Pattern.compile("^do (?:the |that |my )?([a-z]+)(?: one| job)? first$").matcher(c);
        if (first.find()) return new PaIntent(Kind.PING_EDIT, "first:" + first.group(1), 0, Place.NONE, raw);
        if (c.matches("^(?:ping|mark|flag|tag) (?:that|this|it|there|here)(?: spot| place)?$")) return PaIntent.of(Kind.MARK, raw).withPlace(Place.LOOKED);
        java.util.regex.Matcher save = Pattern.compile("^(?:remember|save|mark|call|name|set) (?:this|that|here|this spot|that spot|this place|that place)(?: spot| place)? as (?:the |my |our |a )?([a-z][a-z ]{0,20})$").matcher(c);
        if (save.find()) return new PaIntent(Kind.WAYPOINT, save.group(1).trim(), 0, Place.LOOKED, raw);
        java.util.regex.Matcher find = Pattern.compile("^(?:ping|mark|locate|show me)(?: me)? (?:the |a |an )?(?:nearest |closest )?([a-z]+(?: [a-z]+)?)(?: nearby| near here| around here)?$").matcher(c);
        if (!find.find()) find = Pattern.compile("^(?:find|where(?:'s| is| are)|wheres)(?: me)? (?:the |a |an )?(?:nearest|closest) ([a-z]+(?: [a-z]+)?)(?: nearby| near here| around here)?$").matcher(c);
        else find.reset();
        if (!find.find()) find = Pattern.compile("^find (?:me )?an? (tree|logs|village|chest|bed|cave|river|lake|furnace|crafting table)$").matcher(c);
        else find.reset();
        if (find.find() && !find.group(1).matches("that|this|it|there|here|your bed|you|me|my things|that spot|this spot")) return new PaIntent(Kind.FIND, find.group(1), 0, Place.NONE, raw);
        if (c.matches("^(?:go|walk|run|head|move|get|come)(?: back)?(?: over)? (?:there|to (?:that|this|the) (?:spot|place|point|marker|ping)|to it|over to it|that way)$"))
            return PaIntent.of(Kind.GOTO, raw).withPlace(Place.LOOKED);
        if (c.matches("^(?:go|walk|run|head|take me|get)(?: back)? to (?:where i died|where i fell|my death spot|my death|my things|my stuff)$"))
            return new PaIntent(Kind.GOTO, "death", 0, Place.NONE, raw);
        if (c.matches("^(?:go|walk|run|head)(?: back)? to (?:my|your) bed$")) return new PaIntent(Kind.GOTO, "bed", 0, Place.NONE, raw);
        java.util.regex.Matcher named = Pattern.compile("^(?:go|walk|run|head|take me)(?: back)? to (?:the |my |our )([a-z][a-z ]{0,20})$").matcher(c);
        if (named.find() && !named.group(1).matches("home|base|house|bed|village|chest|chests|farm field")) return new PaIntent(Kind.GOTO, "@" + named.group(1).trim(), 0, Place.NONE, raw);
        if (c.matches("^(?:build|make|put) (?:a |me a )?bridge(?: over (?:that|this|it|there|the water|the lava))?$|^bridge (?:over )?(?:that|this|it|there)$"))
            return PaIntent.of(Kind.BRIDGE, raw).withPlace(Place.LOOKED);
        return null;
    }

    /** [pa33d] Enchanting, the Nether, taming, villager trades, better gear, redstone bits, maps, pillars, trick shots
     *  at a ping, challenges. Null when it's none of these. */
    static PaIntent skills(String c, String raw) {
        java.util.regex.Matcher m;
        m = Pattern.compile("^enchant (?:my |your |the |this |that |some |all )?(sword|pickaxe|axe|bow|armour|armor|helmet|chestplate|leggings|boots|gear|tools|tool|stuff|items|things|it)?(?: .*)?$").matcher(c);
        if (m.find()) return new PaIntent(Kind.ENCHANT, m.group(1) == null ? "gear" : m.group(1), 0, Place.NONE, raw);
        if (c.matches("^(?:come |let us go |go |head |follow me )?(?:to|into|through) the nether(?: with me)?$|^nether trip$|^go nether$")) return new PaIntent(Kind.NETHER, "netherrack", 32, Place.NONE, raw);
        m = Pattern.compile("^(?:get|gather|collect|mine|fetch|bring) (?:me |us )?(\\d+ |some )?(quartz|nether quartz|netherrack|glowstone|soul sand|magma)(?: from the nether| in the nether)?$").matcher(c);
        if (m.find()) {
            int n = m.group(1) == null || m.group(1).trim().equals("some") ? 16 : Integer.parseInt(m.group(1).trim());
            return new PaIntent(Kind.NETHER, m.group(2).replace("nether ", ""), n, Place.NONE, raw);
        }
        m = Pattern.compile("^tame (?:that |this |the |a |an |me a |me an )?(wolf|dog|cat|ocelot|parrot|horse|animal|it|one|that|this)?(?: for me)?$").matcher(c);
        if (m.find()) return new PaIntent(Kind.TAME, m.group(1) == null || m.group(1).matches("it|one|animal|that|this") ? "any" : m.group(1).equals("dog") ? "wolf" : m.group(1), 0, Place.LOOKED, raw);
        m = Pattern.compile("^(?:buy|get|trade for) (?:me |us )?(\\d+ |some |a |an )?([a-z ]+?) from (?:that|this|the|a) villager$").matcher(c);
        if (m.find()) {
            int n = m.group(1) == null || !m.group(1).trim().matches("\\d+") ? 0 : Integer.parseInt(m.group(1).trim());
            return new PaIntent(Kind.VTRADE, "buy:" + m.group(2).trim(), n, Place.LOOKED, raw);
        }
        m = Pattern.compile("^sell (?:my |your |the |some )?(\\d+ )?([a-z ]+?) to (?:that|this|the|a) villager$").matcher(c);
        if (m.find()) return new PaIntent(Kind.VTRADE, "sell:" + m.group(2).trim(), m.group(1) == null ? 0 : Integer.parseInt(m.group(1).trim()), Place.LOOKED, raw);
        if (c.matches("^trade with (?:that|this|the) villager$")) return new PaIntent(Kind.VTRADE, "buy:anything", 0, Place.LOOKED, raw);
        m = Pattern.compile("^(?:brew|make|mix) (?:me |us )?(?:a |an |some |\\d+ )?(healing|health|swiftness|speed|fire resistance|night vision|strength|regeneration|leaping|water breathing)? ?(?:potions?|brew)$").matcher(c);
        if (m.find()) return new PaIntent(Kind.BREW, m.group(1) == null ? "healing" : m.group(1).replace("health", "healing").replace("speed", "swiftness"), 3, Place.NONE, raw);
        if (c.matches("^(?:upgrade|improve) (?:your |its |the )?(?:gear|tools|armour|armor|kit|equipment|stuff|items)$|^gear up$|^kit yourself out$|^(?:make|craft) (?:yourself )?(?:some )?(?:better|new) (?:gear|tools|armour|armor|kit)$|^get yourself (?:some )?better (?:gear|tools|armour|armor)$"))
            return PaIntent.of(Kind.UPGRADE, raw);
        m = Pattern.compile("^(?:place|put|stick|add|fit) (?:a |an |the |me a )?(lever|stone button|oak button|button|oak door|iron door|door|pressure plate|stone pressure plate|redstone torch)(?: .*)?$").matcher(c);
        if (m.find()) return new PaIntent(Kind.PLACE_THING, m.group(1), 1, Place.LOOKED, raw);
        if (c.matches("^(?:map|chart) (?:this|the) (?:area|place|land|region|surroundings)$|^make (?:me )?a map(?: of (?:this|the) area| of here)?$|^draw (?:me )?a map$"))
            return PaIntent.of(Kind.MAP, raw).withPlace(Place.HERE);
        m = Pattern.compile("^(?:pillar|tower|nerd pole) up(?: (\\d+)(?: blocks?)?)?$|^build (?:a |yourself a )?pillar(?: (\\d+)(?: blocks?)?(?: high)?)?$").matcher(c);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            return new PaIntent(Kind.PILLAR, "pillar", g == null ? 5 : Integer.parseInt(g), Place.NONE, raw);
        }
        if (c.matches("^(?:shoot|hit) (?:that |the |this )(?:target|bell|block|ping|button|sign|marker|mark|spot)(?: .*)?$|^trick shot(?: that)?$|^show me a trick shot$|^shoot the ping$")
            || c.matches("^(?:shoot|hit) (?:that|the|this) (?:moving )?(?:pig|cow|sheep|chicken|minecart|cart|horse|target)(?: in (?:the|that) (?:minecart|cart))?(?: as it goes| on the move| while it moves)?$"))   // [pa33e] a moving one too
            return PaIntent.of(Kind.SHOOT_AT, raw).withPlace(Place.LOOKED);
        if (c.matches("^race (?:me|you)(?: to (?:that|the|this) [a-z ]+)?$|^(?:i will |ill |i'll )?race you(?: to .*)?$|^first to (?:that|the) [a-z ]+(?: wins)?$"))
            return new PaIntent(Kind.CHALLENGE, "race", 0, Place.LOOKED, raw);
        if (c.matches("^(?:who can|let us see who can|bet i can|bet you cant|bet you can not) mine (?:the )?(?:more|most|faster)(?: in a minute| in 1 minute)?$|^mining (?:race|contest|competition|challenge)$"))
            return new PaIntent(Kind.CHALLENGE, "mine", 0, Place.NONE, raw);
        if (c.matches("^(?:crossbow|bolt) (?:contest|competition|match|challenge|shoot ?out)$|^(?:shooting|archery) (?:contest|competition|match|challenge) with (?:the |a |your )?crossbows?$"))   // [pa35] the crossbow variant
            return new PaIntent(Kind.CHALLENGE, "xbow", 0, Place.LOOKED, raw);
        if (c.matches("^(?:shooting|archery) (?:contest|competition|match|challenge)$|^(?:who can|let us see who can|bet i can|bet you cant) (?:shoot|hit the target) (?:better|more|best)$"))
            return new PaIntent(Kind.CHALLENGE, "shoot", 0, Place.LOOKED, raw);
        return null;
    }

    /** Cut into steps on commas, "then", "after that", "and then"; on "and" only when a new order starts after it. */
    static List<String> split(String norm) {
        String s = " " + norm + " ";
        s = s.replaceAll(" (?:and then|after that|afterwards|after which|once done|when done|when you are done|when finished|and after) ", " , ");
        s = s.replaceAll(" then ", " , ");
        List<String> parts = new ArrayList<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            // "... and <verb> ..." starts a new order; "64 logs and 32 stone" does not
            String[] ands = t.split(" and ");
            StringBuilder cur = new StringBuilder(ands[0]);
            for (int k = 1; k < ands.length; k++) {
                String right = ands[k].trim();
                if (startsOrder(right) || startsItem(right)) {
                    parts.add(cur.toString().trim());
                    cur = new StringBuilder(right);
                } else {
                    cur.append(" and ").append(right);
                }
            }
            parts.add(cur.toString().trim());
        }
        return parts;
    }

    private static final Set<String> VERBS = Set.of("follow", "stay", "come", "guard", "protect", "defend", "gather",
        "collect", "get", "grab", "fetch", "chop", "cut", "mine", "dig", "farm", "harvest", "plant", "build", "construct",
        "craft", "make", "smelt", "cook", "deposit", "store", "unload", "bring", "give", "hand", "fight", "attack",
        "kill", "hunt", "fish", "breed", "shear", "sort", "organise", "organize", "light", "stop", "cancel", "undo",
        "pause", "resume", "continue", "wait", "hold", "return", "go", "head", "set", "sleep", "rest", "explore",
        "repair", "clear", "flatten", "tidy", "sell", "buy", "fuel", "refuel", "put", "take", "withdraw", "find", "keep",
        "tend", "replant", "till", "place", "forge", "empty", "drop", "feed", "scout", "strip", "branch");

    private static boolean startsOrder(String s) {
        String first = s.split(" ")[0];
        return VERBS.contains(first);
    }

    /** "64 logs and some sticks": the right side is another thing, read with the same verb. */
    private static boolean startsItem(String s) {
        String t = s.replaceFirst("^(?:\\d+ |some |a |an |the |more |a few )+", "");
        String first = t.split(" ")[0];
        if (first.isEmpty()) return false;
        if (RESOURCES.containsKey(first)) return true;
        for (String[] p : CRAFTABLES) if (t.startsWith(p[0])) return true;
        for (String m : MATERIALS) if (first.equals(m) && t.split(" ").length > 1 && TOOLS.contains(t.split(" ")[1])) return true;
        if (BUILDINGS.containsKey(first)) return true;
        return TOOLS.contains(first);
    }

    // ------------------------------------------------------------------ questions and small talk

    private static PaIntent question(String c, String raw) {
        if (c.matches("^(?:what is up|how is it going)(?: .*)?$")) return null;
        if (has(c, "what are you doing", "what you doing", "what are you up to", "whatcha doing", "what is your job",
                "what is the plan", "what are you working on", "are you busy", "what is happening", "what you up to")
            || c.equals("status report") || c.equals("progress") || c.equals("report")) {
            return PaIntent.of(Kind.DOING, raw);
        }
        if (has(c, "how are you", "are you ok", "are you alright", "you ok", "how is your health", "how much health",
                "are you hurt", "are you hungry", "your health", "your hp", "how do you feel", "how you feeling", "how are things") || c.equals("status")) {
            return PaIntent.of(Kind.STATUS, raw);
        }
        if (has(c, "what do you have", "what have you got", "what is in your", "what are you carrying", "show me your inventory",
                "show your inventory", "your inventory", "what is in the pack", "open your pack", "show me your items", "what items")
            || c.equals("inventory") || c.equals("pack")) {
            return PaIntent.of(Kind.INVENTORY, raw);
        }
        if (has(c, "what do you need", "do you need anything", "need anything", "anything you need", "what are you missing", "what do you want")) {
            return PaIntent.of(Kind.NEEDS, raw);
        }
        if (has(c, "where are you", "where is my assistant", "where did you go")) {
            return PaIntent.of(Kind.WHERE, raw);
        }
        if (has(c, "what can you do", "what do you do", "list of commands", "what are your commands", "how do i use you",
                "how do i talk to you", "how do i give you orders", "what can i ask") || c.equals("help") || c.equals("help me")
            || c.equals("commands") || c.equals("options")) {
            return PaIntent.of(Kind.HELP, raw);
        }
        boolean asks = c.matches("^(?:how|what|where|why|when|who|which|whats|is there|are there|does|do i|do we|can i|should i|is it|tell me about|explain|what is) .*")
            || raw.trim().endsWith("?");
        // "can you ...", "could you ...", "will you ..." are orders said politely, not questions
        if (c.matches("^(?:can|could|would|will) you .*")) return null;
        if (asks && !startsOrder(c)) {
            return new PaIntent(Kind.QUESTION, c, 0, Place.NONE, raw);
        }
        return null;
    }

    private static PaIntent smallTalk(String c, String raw) {
        if (c.matches("^(?:yes|ok|do it|go ahead|sounds good|confirm|okay|alright|yes please|go for it|ok go|yes do it|affirmative|correct|right|fine|ok do it)$")) {
            return PaIntent.of(Kind.YES, raw);
        }
        if (c.matches("^(?:no|no thanks|do not|not now|never|negative|no no|no do not)$")) return PaIntent.of(Kind.NO, raw);
        if (c.matches("^(?:thanks|thank you|thanks a lot|good job|well done|nice|nice work|great job|great|awesome|good work|perfect|nice one|cool|amazing|good)(?: .*)?$")
            && !startsOrder(c.replaceAll("^\\S+ ", ""))) {
            return PaIntent.of(Kind.THANKS, raw);
        }
        if (c.matches("^(?:hello|good morning|good evening|good afternoon|morning|evening|what is up|how is it going)(?: there| friend| buddy| mate)?$")) {
            return PaIntent.of(Kind.GREETING, raw);
        }
        if (c.matches("^(?:bye|goodbye|good night|see you|see you later|later|farewell|night)$")) return PaIntent.of(Kind.BYE, raw);
        return null;
    }

    // ------------------------------------------------------------------ one step

    /** "farming", "mining", "chopping": the verb a step starts with, said as doing it. */
    private static final Map<String, String> GERUND = Map.ofEntries(Map.entry("farming", "farm"), Map.entry("mining", "mine"),
        Map.entry("chopping", "chop"), Map.entry("fishing", "fish"), Map.entry("building", "build"), Map.entry("following", "follow"),
        Map.entry("hunting", "hunt"), Map.entry("gathering", "gather"), Map.entry("digging", "dig"), Map.entry("crafting", "craft"),
        Map.entry("guarding", "guard"), Map.entry("smelting", "smelt"), Map.entry("cooking", "cook"), Map.entry("harvesting", "harvest"),
        Map.entry("collecting", "collect"), Map.entry("exploring", "explore"), Map.entry("sorting", "sort"), Map.entry("planting", "plant"));

    static PaIntent clause(String c, PaIntent prev, String raw) {
        PaIntent pl = places(c, raw);                                   // [pa33c-2b] "go there" as one step of several
        if (pl != null && pl.kind() != Kind.FIND) return pl;
        PaIntent sk = skills(c, raw);                                   // [pa33d] "enchant my sword", "tame that wolf" as one step of several
        if (sk != null && sk.kind() != Kind.CHALLENGE) return sk;
        String g = GERUND.get(c.split(" ")[0]);
        if (g != null) c = g + c.substring(c.indexOf(' ') < 0 ? c.length() : c.indexOf(' '));
        int amount = amount(c);
        Place place = place(c);
        String first = c.split(" ")[0];

        // control first: these must always win
        if (c.matches("^(?:stop|halt|freeze|cancel|abort|never mind|nevermind|forget it|stand down|enough|quit)(?: .*)?$")
            && !c.matches("^stop (?:being|following|guarding) .*") && !has(c, "auto help", "helping on your own")) {
            if (c.matches("^cancel (?:the )?last(?: one| order| job)?$")) return PaIntent.of(Kind.UNDO, raw);
            return PaIntent.of(Kind.STOP, raw);
        }
        if (c.matches("^(?:undo|take that back|undo that|go back|cancel last(?: order| job)?|revert)(?: .*)?$")) return PaIntent.of(Kind.UNDO, raw);
        if (c.matches("^(?:pause|hold on|hang on|hold up|wait up|wait a (?:sec|second|moment|minute)|one sec|one moment|take a break)(?: .*)?$")) return PaIntent.of(Kind.PAUSE, raw);
        if (c.matches("^(?:resume|continue|carry on|keep going|keep at it|keep it up|go on|back to work|unpause|get back to work)(?: .*)?$")) return PaIntent.of(Kind.RESUME, raw);
        Matcher rn = RENAME.matcher(c);
        if (rn.matches()) return new PaIntent(Kind.RENAME, PaNames.asSaid(raw, c, rn.start(1), rn.end(1), 16, rn.group(1)), 0, Place.NONE, raw);   // [pa36b] the name as said

        // auto-help
        if (has(c, "auto help on", "turn on auto help", "help out on your own", "do your own thing", "be useful",
                "help me out", "look after the base", "help on your own", "work on your own", "use your initiative", "autopilot on")) {
            return PaIntent.of(Kind.AUTO_ON, raw);
        }
        if (has(c, "auto help off", "turn off auto help", "only do what i say", "stop helping on your own", "no auto help",
                "stop doing your own thing", "autopilot off", "stop auto help", "just follow orders")) {
            return PaIntent.of(Kind.AUTO_OFF, raw);
        }

        // moving and standing
        if (c.matches("^(?:follow|stick with|tag along|come with|keep up|walk with|stay with|stay close|stay near|heel|follow along|keep close)(?: .*)?$")
            && !has(c, "stop following")) {
            return PaIntent.of(Kind.FOLLOW, raw);
        }
        if (c.matches("^(?:stop following|do not follow|quit following)(?: .*)?$") || c.matches("^(?:stay|wait|hold (?:position|still|on here)|stand (?:still|here|there|by)|do not move|halt here|park|remain|sit|stay put|hold it)(?: .*)?$")) {
            if (c.matches("^stay (?:with|close|near) .*")) return PaIntent.of(Kind.FOLLOW, raw);
            return PaIntent.of(Kind.STAY, raw);
        }
        if (c.matches("^(?:come|get over here|over here|to me|here boy|here|come back|come to me|run to me|hurry over|teleport to me|join me|find me$|meet me)(?: .*)?$")
            && !c.matches("^come with .*")) {
            return PaIntent.of(Kind.COME, raw).withPlace(Place.ME);
        }
        if (c.matches("^(?:guard|protect|defend|watch over|keep watch|watch|look after|keep me safe|cover|escort|bodyguard)(?: .*)?$")
            && !has(c, "watch out")) {
            String what = guardTarget(c);
            if (what.isEmpty()) return PaIntent.of(Kind.GUARD_ME, raw).withPlace(Place.ME);
            return new PaIntent(Kind.GUARD_HERE, what, 0, place == Place.NONE ? Place.HERE : place, raw);
        }
        // [pa33c-2b] its bed (where it comes back after a fall)
        if (c.matches("^(?:where(?:'s| is| are)? (?:is )?your bed|where do you sleep|where(?:'s| is) your spawn|show me your bed|which bed is yours|have you got a bed|do you have a bed)(?: .*)?$"))
            return PaIntent.of(Kind.BED_WHERE, raw);
        if (c.matches("^(?:use|take|have|claim) (?:this|that|the|my spare) bed(?: .*)?$") || c.matches("^(?:sleep|spawn) (?:in|at|on) (?:this|that) bed(?: .*)?$")
            || c.matches("^(?:this|that)(?:'s| is) your bed(?: .*)?$") || c.matches("^(?:make|set) (?:this|that)(?: bed)? (?:as )?your (?:bed|spawn)(?: .*)?$")
            || c.matches("^set (?:your )?spawn (?:at|to|on) (?:this|that) bed(?: .*)?$") || c.matches("^use (?:this|that) (?:as|for) your (?:bed|spawn)(?: .*)?$"))
            return PaIntent.of(Kind.BED_USE, raw).withPlace(Place.LOOKED);
        if (c.matches("^(?:place|put|set up|make|build|lay down|get) (?:yourself |down )?(?:a|your|a new|your own) bed(?: .*)?$") || c.matches("^put your bed (?:down|here|there|by mine|next to mine)(?: .*)?$"))
            return PaIntent.of(Kind.BED_PLACE, raw).withPlace(place == Place.NONE ? Place.HERE : place);
        if (c.matches("^(?:wake up|wakey wakey|wake|get up|up you get|rise and shine|morning sleepy ?head|stop sleeping|out of bed)(?: .*)?$")) return PaIntent.of(Kind.WAKE, raw);   // [pa33d]
        if (c.matches("^(?:time for bed|bed ?time|time to sleep|time for sleep|go to bed|go to sleep|get some sleep|get to bed)(?: now)?$")) return PaIntent.of(Kind.SLEEP, raw);
        if (c.matches("^(?:sleep|go to (?:bed|sleep)|to (?:bed|sleep)|rest|take shelter|hide|get inside|shelter)(?: .*)?$")) return PaIntent.of(Kind.SLEEP, raw);
        if (c.matches("^(?:go|head|return|walk|run|get|travel)? ?(?:back )?(?:home|to (?:the |your |my )?(?:home|base|house|bed))(?: .*)?$")
            || c.matches("^(?:return|go back|head back|go home|back to base|retreat)(?: .*)?$")) {
            return PaIntent.of(Kind.GO_HOME, raw).withPlace(Place.HOME);
        }
        if (c.matches("^(?:set (?:your |the )?home|make (?:this|here) (?:your |the )?home|this is (?:your |our )?home|home is here|live here|your home is here|set spawn)(?: .*)?$")) {
            return PaIntent.of(Kind.SET_HOME, raw).withPlace(Place.HERE);
        }
        if (c.matches("^(?:explore|scout|look around|wander|go exploring|search the area|survey)(?: .*)?$")) return PaIntent.of(Kind.EXPLORE, raw);

        // fighting and hunting
        if (c.matches("^(?:fight|attack|kill|slay|hit|shoot|take out|get rid of|deal with|clear out|defeat|destroy)(?: .*)?$")) {
            String animal = findIn(c, ANIMALS);
            if (!animal.isEmpty()) return new PaIntent(Kind.HUNT, animal, amount, Place.NONE, raw);
            String mob = findIn(c, MONSTERS);
            Place p = has(c, "that", "this", "it", "him", "them", "those") ? Place.LOOKED : Place.NONE;
            if (c.matches("^(?:clear out|clear) .*") && mob.isEmpty()) return new PaIntent(Kind.CLEAR_AREA, area(c), 0, place, raw);
            return new PaIntent(Kind.FIGHT, mob.isEmpty() ? "monsters" : mob, 0, p, raw);
        }
        if (c.matches("^(?:hunt|slaughter|butcher)(?: .*)?$")) {
            String animal = findIn(c, ANIMALS);
            if (animal.isEmpty() && has(c, "food", "meat")) animal = "animals";
            return new PaIntent(Kind.HUNT, animal.isEmpty() ? "animals" : animal, amount, Place.NONE, raw);
        }
        if (c.matches("^(?:go )?(?:fish|fishing|catch(?: some)? fish|catch)(?: .*)?$") && (has(c, "fish") || first.equals("catch"))) {
            return new PaIntent(Kind.FISH, "fish", amount, Place.NONE, raw);
        }
        if (c.matches("^(?:breed|mate)(?: .*)?$")) return new PaIntent(Kind.BREED, orDefault(findIn(c, ANIMALS), "animals"), amount, Place.NONE, raw);
        if (c.matches("^shear(?: .*)?$")) return new PaIntent(Kind.SHEAR, "sheep", amount, Place.NONE, raw);

        // food for the player
        if (has(c, "i am hungry", "feed me", "bring me food", "give me food", "get me food", "bring food", "i need food",
                "something to eat", "bring me something to eat", "i am starving", "get me something to eat", "give me something to eat",
                "food please", "need food")) {
            return new PaIntent(Kind.BRING_FOOD, "food", amount, Place.ME, raw);
        }

        // the base
        if (c.matches("^(?:light|place torches|put torches|torch|illuminate|brighten)(?: .*)?$") || has(c, "light up", "put down torches", "place some torches")
            || (c.matches("^(?:put|place|set|stick|lay)(?: .*)?$") && word(c, "torches") && !has(c, "chest", "barrel"))) {
            return new PaIntent(Kind.LIGHT_AREA, "torches", amount, place == Place.NONE ? Place.HERE : place, raw);
        }
        if (c.matches("^(?:sort|organise|organize|tidy|arrange)(?: .*)?$") && !has(c, "tidy up the area")) {
            return new PaIntent(Kind.SORT, "chest", 0, place, raw);
        }
        if (c.matches("^(?:clear|flatten|level)(?: .*)?$")) {
            return new PaIntent(Kind.CLEAR_AREA, area(c), 0, place == Place.NONE ? Place.HERE : place, raw);
        }
        if (c.matches("^(?:fuel|refuel|keep the furnace|feed the furnace|top up the furnace|stoke)(?: .*)?$") || has(c, "furnace fuelled", "furnace fueled", "furnace going")) {
            return new PaIntent(Kind.FUEL, "furnace", 0, place, raw);
        }
        if (c.matches("^(?:repair|fix|mend)(?: .*)?$")) return new PaIntent(Kind.REPAIR, rest(c), 0, place, raw);

        // trade
        if (c.matches("^(?:buy|purchase|shop for|order)(?: .*)?$")) {
            String it = itemPhrase(c.replaceFirst("^(?:buy|purchase|shop for|order) ", ""));
            return new PaIntent(Kind.TRADE_BUY, it, amount, Place.NONE, raw);
        }
        if (c.matches("^(?:sell|trade)(?: .*)?$")) {
            String it = itemPhrase(c.replaceFirst("^(?:sell|trade)(?: away)? ", ""));
            return new PaIntent(Kind.TRADE_SELL, it, amount, Place.NONE, raw);
        }

        // putting things away and fetching them
        if (c.matches("^(?:deposit|store|unload|dump|empty)(?: .*)?$")
            || c.matches("^(?:put|drop|place|stick|throw|toss|chuck|shove) (?:it|them|everything|all|your items|the items|your pack|items|that|those|all of it|it all|what you have|your things)(?: .*)? (?:in|into|inside) .*")
            || c.matches("^(?:put|place|store) (?:it |them |everything |items )?away(?: .*)?$")) {
            Place p = has(c, "that chest", "this chest", "that barrel", "there") ? Place.LOOKED : place;
            return new PaIntent(Kind.DEPOSIT, "", 0, p, raw);
        }
        if (c.matches("^(?:get|grab|take|withdraw|fetch|bring)(?: me)? .* (?:from|out of) (?:the |that |this |my |a )?(?:chest|barrel|storage|stash|box).*")) {
            String thing = c.replaceFirst("^(?:get|grab|take|withdraw|fetch|bring)(?: me)? ", "").replaceFirst(" (?:from|out of) .*$", "");
            return new PaIntent(Kind.FETCH, itemPhrase(thing), amount, has(c, "that chest", "this chest") ? Place.LOOKED : Place.NONE, raw);
        }
        if (c.matches("^(?:give|hand|pass|toss|throw|bring|drop)(?: over)? (?:me|us|it|them|over)(?: .*)?$")) {
            String thing = c.replaceFirst("^(?:give|hand|pass|toss|throw|bring|drop)(?: over)? (?:me|us|it|them|over) ?", "");
            String it = itemPhrase(thing);
            if (it.equals("food")) return new PaIntent(Kind.BRING_FOOD, "food", amount, Place.ME, raw);
            if (it.isEmpty() || it.equals("to")) it = has(c, "it", "them") ? "it" : "";
            if (!it.isEmpty()) {
                // "bring me 16 logs": gather if it has none is PaOrders' call; the reading is GIVE
                return new PaIntent(Kind.GIVE, it, amount, Place.ME, raw);
            }
        }

        // building and making
        if (c.matches("^(?:build|construct|erect|put up|raise|make|create|set up|design|fortify|wall off)(?: .*)?$")
            || (c.matches("^put (?:a|an|the|up|me|us) .*") && !building(stripPlace(c)).isEmpty())) {
            String bld = c.matches("^(?:fortify|wall off).*") ? "fortify" : building(stripPlace(c));
            if (!bld.isEmpty()) {
                if (bld.equals("farm")) return new PaIntent(Kind.FARM, "field", 0, place == Place.NONE ? Place.HERE : place, raw);
                return new PaIntent(Kind.BUILD, bld, 0, place == Place.NONE ? Place.HERE : place, raw);
            }
            String made = craftable(stripPlace(c));
            if (!made.isEmpty()) return new PaIntent(Kind.CRAFT, made, amount, place, raw);
            if (first.equals("build") || first.equals("construct") || first.equals("erect")) {
                return new PaIntent(Kind.UNKNOWN, "build", 0, place, raw);
            }
            return new PaIntent(Kind.UNKNOWN, "craft", 0, place, raw);
        }
        if (c.matches("^(?:craft|forge|assemble|fashion|whip up|knock up|produce)(?: .*)?$")) {
            String made = craftable(c);
            if (!made.isEmpty()) return new PaIntent(Kind.CRAFT, made, amount, place, raw);
            return new PaIntent(Kind.UNKNOWN, "craft", 0, place, raw);
        }
        if (c.matches("^(?:smelt|cook|bake|roast|melt|refine|fire|burn)(?: .*)?$")) {
            String it = rest(c).replaceAll("\\b(?:the|some|all|of|your|my|in|a|an)\\b", "").replaceAll("\\d+", "").trim().replaceAll("\\s+", " ");
            if (it.matches(".*(?:raw )?iron.*")) it = "iron";
            else if (it.matches(".*gold.*")) it = "gold";
            else if (it.matches(".*copper.*")) it = "copper";
            else if (it.matches(".*(?:beef|steak).*")) it = "beef";
            else if (it.matches(".*(?:pork|porkchop).*")) it = "porkchop";
            else if (it.matches(".*chicken.*")) it = "chicken";
            else if (it.matches(".*(?:mutton|lamb).*")) it = "mutton";
            else if (it.matches(".*(?:fish|cod|salmon).*")) it = "fish";
            else if (it.matches(".*(?:meat|food).*")) it = "food";
            else if (it.matches(".*(?:logs|charcoal).*")) it = "logs";
            else if (it.matches(".*(?:sand|glass).*")) it = "sand";
            else if (it.matches(".*(?:cobblestone|stone).*")) it = "cobblestone";
            else if (it.matches(".*(?:potato).*")) it = "potato";
            else if (it.matches(".*(?:ore|ores).*")) it = "ore";
            if (it.isEmpty()) return new PaIntent(Kind.UNKNOWN, "smelt", 0, place, raw);
            return new PaIntent(Kind.SMELT, it, amount, place, raw);
        }

        // mining
        if (c.matches("^(?:mine|dig|strip mine|branch mine|tunnel|excavate|quarry|go mining|start mining|look for|search for|prospect)(?: .*)?$")) {
            Matcher lv = LEVEL.matcher(c);
            String ore = findIn(c, ORES);
            if (has(c, "vein", "that ore", "this ore") || (has(c, "this", "that", "these", "those") && !has(c, "down"))) {
                return new PaIntent(Kind.MINE_ORE, ore.isEmpty() ? "vein" : ore, amount, Place.LOOKED, raw);
            }
            if (!ore.isEmpty()) return new PaIntent(Kind.MINE_ORE, ore, amount, Place.NONE, raw);
            if (lv.find()) return new PaIntent(Kind.MINE_DOWN, "level", Integer.parseInt(lv.group(1)), Place.NONE, raw);
            if (has(c, "down", "deep", "a mine", "shaft", "tunnel", "strip", "branch", "underground", "a hole", "staircase", "mineshaft")
                || first.equals("tunnel") || first.equals("strip") || first.equals("branch") || first.equals("excavate")) {
                return new PaIntent(Kind.MINE_DOWN, "level", 12, Place.NONE, raw);
            }
            String res = findIn(c, RESOURCES.keySet());
            if (!res.isEmpty()) return new PaIntent(Kind.GATHER, RESOURCES.get(res), amount, Place.NONE, raw);
            if (c.matches("^(?:look for|search for) .*")) return new PaIntent(Kind.UNKNOWN, "gather", 0, place, raw);
            return new PaIntent(Kind.UNKNOWN, "mine", 0, place, raw);
        }

        // farming
        if (c.matches("^(?:farm|tend|plant|till|replant|sow|water|grow|work the|harvest)(?: .*)?$") || has(c, "the farm", "the crops", "the field")) {
            String res = findIn(c, RESOURCES.keySet());
            boolean crop = res.isEmpty() || CROPS.contains(res) || res.equals("seeds") || res.equals("food");
            if (crop || first.equals("farm") || first.equals("tend") || first.equals("plant") || first.equals("till")
                || first.equals("replant") || first.equals("sow")) {
                Place p = has(c, "this field", "this farm", "that field", "that farm") ? Place.LOOKED : place;
                return new PaIntent(Kind.FARM, res.isEmpty() || res.equals("food") ? "crops" : RESOURCES.get(res), amount, p, raw);
            }
        }

        // gathering (the commonest order of all)
        if (GATHER_V.contains(first) || first.equals("punch") || c.matches("^(?:go )?(?:get|gather|collect) .*")) {
            // [pa33c-2b] "chop that" / "chop this tree": the one pointed at (or pinged)
            if (c.matches("^(?:chop|cut|fell)(?: down)? (?:that|this|it|these|those)(?: logs| 1| one)?$")) return new PaIntent(Kind.GATHER, "logs", amount, Place.LOOKED, raw);
            if ((first.equals("chop") || first.equals("cut") || first.equals("punch")) && findIn(c, RESOURCES.keySet()).isEmpty()) {
                return new PaIntent(Kind.GATHER, "logs", amount, place, raw);
            }
            String res = findIn(c, RESOURCES.keySet());
            if (!res.isEmpty()) {
                String r = RESOURCES.get(res);
                if (r.equals("food")) return new PaIntent(Kind.BRING_FOOD, "food", amount, Place.ME, raw);
                if (CROPS.contains(r)) return new PaIntent(Kind.FARM, r, amount, place, raw);
                if (ORES.contains(r) && !r.equals("iron") && !r.equals("coal")) return new PaIntent(Kind.MINE_ORE, r, amount, Place.NONE, raw);
                return new PaIntent(Kind.GATHER, r, amount, place, raw);
            }
            String made = craftable(c);
            // [pa33b] "fetch me some bread": a thing brought to you (made first if need be), not just made
            if (!made.isEmpty() && c.matches("^(?:fetch|bring|grab|get) (?:me|us) .*")) return new PaIntent(Kind.GIVE, made, amount, Place.ME, raw);
            if (!made.isEmpty()) return new PaIntent(Kind.CRAFT, made, amount, place, raw);
            if (findIn(c, ANIMALS).length() > 0) return new PaIntent(Kind.HUNT, findIn(c, ANIMALS), amount, Place.NONE, raw);
            return new PaIntent(Kind.UNKNOWN, "gather", 0, place, raw);
        }

        // no verb: carry the last one over ("chop 64 logs and 32 stone", "make planks and sticks")
        if (prev != null) {
            switch (prev.kind()) {
                case GATHER -> {
                    String res = findIn(c, RESOURCES.keySet());
                    if (!res.isEmpty()) return new PaIntent(Kind.GATHER, RESOURCES.get(res), amount, place, raw);
                }
                case CRAFT -> {
                    String made = craftable(c);
                    if (!made.isEmpty()) return new PaIntent(Kind.CRAFT, made, amount, place, raw);
                }
                case BUILD -> {
                    String bld = building(stripPlace(c));
                    if (!bld.isEmpty() && !bld.equals("farm")) return new PaIntent(Kind.BUILD, bld, 0, place == Place.NONE ? prev.place() : place, raw);
                }
                case SMELT -> {
                    String res = findIn(c, RESOURCES.keySet());
                    if (!res.isEmpty()) return new PaIntent(Kind.SMELT, RESOURCES.get(res), amount, place, raw);
                }
                default -> { }
            }
        }
        // a bare thing: "logs", "64 stone", "a pickaxe"
        String res = findIn(c, RESOURCES.keySet());
        if (!res.isEmpty() && c.split(" ").length <= 3) return new PaIntent(Kind.GATHER, RESOURCES.get(res), amount, place, raw);
        String made = craftable(c);
        if (!made.isEmpty() && c.split(" ").length <= 3) return new PaIntent(Kind.CRAFT, made, amount, place, raw);
        return new PaIntent(Kind.UNKNOWN, "", 0, place, raw);
    }

    // ------------------------------------------------------------------ the parts of a step

    static int amount(String c) {
        Matcher st = STACKS.matcher(c);
        if (st.find()) return Math.min(1024, Integer.parseInt(st.group(1)) * 64);
        if (A_STACK.matcher(c).find()) return 64;
        String noLevel = LEVEL.matcher(c).replaceAll(" ");
        noLevel = AREA.matcher(noLevel).replaceAll(" ");
        Matcher m = NUM.matcher(noLevel);
        if (m.find()) return Math.max(1, Math.min(1024, Integer.parseInt(m.group(1))));
        return 0;
    }

    static Place place(String c) {
        if (has(c, "where i am looking", "where i look", "where i am pointing", "that spot", "over there", "there")) return Place.LOOKED;
        if (has(c, "by my house", "near my house", "at my house", "by the house", "at home", "at the base", "by the base",
                "at base", "near the base", "by home", "near home", "at my base", "by my base", "next to my house", "beside my house")) return Place.HOME;
        if (has(c, "here", "this spot", "right here", "this place", "on this spot", "where i am", "where i stand", "near me", "by me", "next to me")) return Place.HERE;
        if (has(c, "to me", "for me")) return Place.NONE;
        return Place.NONE;
    }

    private static String area(String c) {
        Matcher m = AREA.matcher(c);
        return m.find() ? m.group(1) + "x" + m.group(2) : "8x8";
    }

    private static String guardTarget(String c) {
        String r = c.replaceFirst("^(?:guard|protect|defend|watch over|keep watch over|keep watch|watch|look after|cover|escort|bodyguard)\\s*", "")
            .replaceAll("\\b(?:the|my|our|this|that|area|please|for me|while i .*)\\b", " ").replaceAll("\\s+", " ").trim();
        if (r.isEmpty() || r.equals("me") || r.equals("us") || r.startsWith("me ") || r.equals("keep me safe") || r.equals("back")
            || r.equals("six") || r.equals("me back")) return "";
        if (r.matches("^(?:here|spot|place|right here|position)$") || c.contains("here")) return "here";
        if (r.contains("farm") || r.contains("field") || r.contains("crops")) return "farm";
        if (r.contains("base") || r.contains("house") || r.contains("home")) return "base";
        if (r.contains("gate") || r.contains("door")) return "gate";
        if (r.contains("animals") || r.contains("pen")) return "pen";
        return "here";
    }

    private static String building(String c) {
        if (has(c, "farm", "field", "allotment", "garden", "wheat field", "crop field") && !has(c, "farmhouse")) return "farm";
        String w = findIn(c, BUILDINGS.keySet());
        return w.isEmpty() ? "" : BUILDINGS.get(w);
    }

    /** The sentence without its "by my house", "over there": so a place is not read as the thing to build. */
    static String stripPlace(String c) {
        return (" " + c + " ").replaceAll(" (?:by|near|at|next to|beside) (?:my|the|our) (?:house|base|home) ", " ")
            .replaceAll(" (?:at|by|near) (?:home|base) ", " ").replaceAll("\\s+", " ").trim();
    }

    /** The thing to be made, as a phrase the recipe book understands ("stone pickaxe", "torches"). */
    static String craftable(String c) {
        for (String t : TOOLS) {
            if (word(c, t)) {
                for (String m : MATERIALS) {
                    if (word(c, m)) return m + " " + t;
                }
                if (word(c, "wood")) return "wooden " + t;
                if (word(c, "gold")) return "golden " + t;
                return t;
            }
        }
        for (String[] p : CRAFTABLES) {
            if (word(c, p[0])) {
                if (p[1].equals("helmet") || p[1].equals("chestplate") || p[1].equals("leggings") || p[1].equals("boots") || p[1].equals("armor")) {
                    for (String m : List.of("leather", "iron", "golden", "diamond", "chainmail")) if (word(c, m)) return m + " " + p[1];
                    if (word(c, "gold")) return "golden " + p[1];
                }
                return p[1];
            }
        }
        return "";
    }

    /** The thing named in an order, as plainly as can be said ("16 logs" -> "logs", "the iron" -> "iron"). */
    static String itemPhrase(String s) {
        String made = craftable(s);
        String res = findIn(s, RESOURCES.keySet());
        if (!res.isEmpty() && (made.isEmpty() || s.indexOf(res) < s.indexOf(made.split(" ")[made.split(" ").length - 1]))) {
            return RESOURCES.get(res);
        }
        if (!made.isEmpty()) return made;
        String t = s.replaceAll("\\b(?:the|some|a|an|of|your|my|all|any|few|more|me|it|them|please|now|to|for)\\b", " ").replaceAll("\\d+", " ")
            .replaceAll("\\s+", " ").trim();
        return t;
    }

    private static String rest(String c) {
        int sp = c.indexOf(' ');
        return sp < 0 ? "" : c.substring(sp + 1).trim();
    }

    private static String orDefault(String s, String d) {
        return s.isEmpty() ? d : s;
    }

    private static String findIn(String c, java.util.Collection<String> words) {
        String best = "";
        int at = Integer.MAX_VALUE;
        for (String w : words) {
            int i = indexOfWord(c, w);
            if (i >= 0 && i < at) { at = i; best = w; }
        }
        return best;
    }

    private static int indexOfWord(String c, String w) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(c);
        return m.find() ? m.start() : -1;
    }

    static boolean word(String c, String w) {
        return indexOfWord(c, w) >= 0;
    }

    static boolean has(String c, String... phrases) {
        for (String p : phrases) {
            if (word(c, p)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ asking back

    /** The three to try first, for a new player and an empty sentence. */
    public static List<Choice> starters() {
        return List.of(new Choice("Follow me", "follow me"), new Choice("Get me 16 logs", "get me 16 logs"),
            new Choice("Build a small house here", "build a small house here"));
    }

    private static Clarify clarifyFor(String c, PaIntent i) {
        switch (i.item()) {
            case "build" -> {
                return new Clarify("What shall I build?", List.of(new Choice("A small house", "build a small house here"),
                    new Choice("A shelter", "build a shelter here"), new Choice("A farm", "build a farm here"),
                    new Choice("A storage shed", "build a storage here"), new Choice("A watchtower", "build a watchtower here")));
            }
            case "craft" -> {
                return new Clarify("What shall I make?", List.of(new Choice("A pickaxe", "craft a stone pickaxe"),
                    new Choice("Torches", "craft 16 torches"), new Choice("A chest", "craft a chest"),
                    new Choice("A furnace", "craft a furnace"), new Choice("Planks", "craft 16 planks")));
            }
            case "smelt" -> {
                return new Clarify("What shall I smelt?", List.of(new Choice("Iron", "smelt 8 iron"),
                    new Choice("Cook food", "cook the meat"), new Choice("Charcoal", "smelt 8 logs"),
                    new Choice("Glass", "smelt 8 sand")));
            }
            case "mine" -> {
                return new Clarify("Mine for what?", List.of(new Choice("Iron", "mine iron"), new Choice("Coal", "mine coal"),
                    new Choice("Diamonds", "mine for diamonds"), new Choice("Stone", "mine 32 stone"),
                    new Choice("Dig a mine down", "dig a mine down to level 12")));
            }
            case "gather" -> {
                String unknown = c.replaceFirst("^\\S+ ", "").replaceAll("\\b(?:me|some|the|a|an|of|more|\\d+)\\b", " ").trim().replaceAll("\\s+", " ");
                String q = unknown.isEmpty() ? "Gather what?" : "I don't know how to get \"" + unknown + "\". Did you mean one of these?";
                return new Clarify(q, List.of(new Choice("Logs", "get 16 logs"), new Choice("Stone", "get 32 stone"),
                    new Choice("Iron", "mine iron"), new Choice("Food", "bring me food"), new Choice("Sand", "get 16 sand")));
            }
            default -> {
                return notSure(c);
            }
        }
    }

    /** Orders a player might mean, for "did you mean" and for the talk box's autocomplete. */
    /**
     * [pa33b] British ways of saying the everyday orders, for the recognisers' phrase grammar (a phrase list is what
     * makes recognition hold up across accents) alongside the examples. Each is itself understood (tests).
     */
    public static final List<String> BRITISH_SAYINGS = List.of(
        "fetch us some logs", "grab us sixteen logs", "get us some stone", "chuck it in the chest", "pop it in the chest",
        "bung it in the chest", "nip over here", "come here mate", "right follow me", "follow me mate", "cheers", "cheers mate",
        "ta", "sorted", "nice one", "hang on", "hold on a sec", "half a mo", "crack on", "carry on", "pack it in", "leave it",
        "stay put", "wait there", "have a go at mining", "have a go at mining iron", "go and get some logs", "go and fetch some coal",
        "you alright", "what are you up to", "whereabouts are you", "have a look for diamonds", "make me some armour",
        "light up the area with torches", "sort out the chests", "guard me", "farm this field", "build me a small house here");

    /** [pa33b] Every phrase the recognisers are told to listen for: the examples and the British sayings. */
    public static List<String> grammar() {
        List<String> all = new java.util.ArrayList<>(EXAMPLES);
        for (String b : BRITISH_SAYINGS) if (!all.contains(b)) all.add(b);
        return all;
    }

    public static final List<String> EXAMPLES = List.of(
        "follow me", "stay here", "come here", "guard me", "guard the farm", "go home", "set home here",
        "get me 16 logs", "chop 64 logs", "gather 32 stone", "mine iron until you have 20", "mine for diamonds",
        "dig a mine down to level 12", "mine this vein", "farm this field", "harvest the wheat", "build a small house here",
        "build a shelter here", "build a farm here", "build a storage here", "build a watchtower here", "craft a stone pickaxe",
        "craft 16 torches", "craft a chest", "craft a furnace", "make planks and sticks", "smelt 8 iron", "cook the meat",
        "put everything in that chest", "deposit your items", "get 10 iron from the chest", "bring me food",
        "give me 16 logs", "attack that", "kill the zombies", "hunt 3 cows", "catch 5 fish", "breed the cows",
        "shear the sheep", "light up the area", "sort the chests", "clear a 10x10 area", "keep the furnace fuelled",
        "buy 8 bread", "sell 32 cobblestone", "repair your pickaxe", "what are you doing", "how are you",
        "what do you have", "what do you need", "where are you", "what can you do", "stop", "undo", "pause", "resume",
        "do your own thing", "only do what i say", "go to bed", "explore", "your name is bob",
        "how do i start a business");

    private static Clarify notSure(String c) {
        List<Choice> picks = new ArrayList<>();
        for (String e : nearest(c, 3)) picks.add(new Choice(cap(e), e));
        picks.add(new Choice("What can you do?", "what can you do"));
        return new Clarify("I'm not sure what you mean. Did you mean one of these?", picks);
    }

    /** The examples sharing most words with what was said. */
    public static List<String> nearest(String c, int most) {
        List<String> words = List.of(c.split(" "));
        List<String> scored = new ArrayList<>(EXAMPLES);
        scored.sort((a, b) -> Integer.compare(score(b, words), score(a, words)));
        return scored.subList(0, Math.min(most, scored.size()));
    }

    private static int score(String example, List<String> words) {
        int s = 0;
        for (String w : example.split(" ")) {
            if (w.length() > 2 && words.contains(w)) s += 3;
        }
        for (String w : words) {
            if (w.length() > 3 && example.contains(w)) s += 1;
        }
        return s;
    }

    /** Autocomplete for the talk box: examples starting with (then containing) what is typed so far. */
    public static List<String> complete(String typed, int most) {
        String t = PaWords.clean(typed);
        List<String> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        for (String e : EXAMPLES) if (e.startsWith(t) && !e.equals(t)) out.add(e);
        for (String e : EXAMPLES) if (!e.startsWith(t) && e.contains(" " + t) && out.size() < most * 2) out.add(e);
        return out.subList(0, Math.min(most, out.size()));
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
