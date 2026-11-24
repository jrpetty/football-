package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaLogiOrder.Op;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Ref;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Src;
import com.jrpetty.mcassistant.pa.PaQty.Qty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa36] How the Player AI Assistant reads chest, gathering and logistics orders: "gather 100 stone and put it in this
 * chest", "chop 3 stacks of oak and drop it in the wood chest", "keep this chest stocked with 64 bread", "bring me 20
 * torches from my chests", "how much iron do we have", "haul from the mine to base until I say stop" and a few hundred
 * more, typed or spoken, British or not, typos and all.
 *
 * <p>Asked first by PaUnderstand (one small hook): a sentence that is about a container, a standing stock job, a count
 * or a haul comes back as steps (LOGI intents carrying a PaLogiOrder, mixed with the reader's own intents for the
 * parts that are plain work: "mine iron until you have 32, then smelt it, then put the ingots in my storage" is
 * MINE_ORE, SMELT, LOGI). Anything else comes back null and the reader reads it as it always did. Pure Java.
 */
public final class PaLogiParse {

    private PaLogiParse() {}

    // ------------------------------------------------------------------ words

    /** Container words (after plain-making: plurals and typos put right). */
    static final String CONT = "(?:(?:trapped chest|double chest|ender chest|shulker box|shulker|chest|barrel|hopper|blast furnace|furnace|smoker|brewing stand|composter|dispenser|dropper|storage|stash|drop off|container|crate|bin|box)(?:es|s)?)";
    private static final Pattern HAS_CONT = Pattern.compile("\\b" + CONT + "\\b");
    static { assert true; }
    private static final String DET = "(?:the|that|this|my|our|a|an|your|its|those|these|them|one|1)";
    /** Verbs that put things into something. */
    private static final String PUT = "(?:put|place|drop|store|stick|dump|deposit|leave|stash|throw|toss|chuck|shove|load|stuff|pack|bung|pop|lob|unload|transfer|move|bring|take|carry|send|empty|tip|stack)";
    /** Verbs that get things from the world. */
    private static final String GET = "(?:gather|collect|get|grab|mine|chop|cut|dig|harvest|farm|fetch|find|quarry|fell|punch|bring|obtain|scavenge|round up|pick up|pick|go get|go and get)";
    private static final Pattern IN = Pattern.compile("\\b(?:in|into|inside|in to|to|onto|on)\\b");
    private static final Map<String, String> WORDS = Map.ofEntries(
        Map.entry("chets", "chest"), Map.entry("chset", "chest"), Map.entry("chesst", "chest"), Map.entry("chestt", "chest"),
        Map.entry("cheast", "chest"), Map.entry("chess", "chest"), Map.entry("chest's", "chest"), Map.entry("barell", "barrel"),
        Map.entry("barrle", "barrel"), Map.entry("barel", "barrel"), Map.entry("crate", "chest"), Map.entry("trunk", "chest"),
        Map.entry("shulkr", "shulker"), Map.entry("sholker", "shulker"), Map.entry("shulka", "shulker"), Map.entry("schulker", "shulker"),
        Map.entry("hoper", "hopper"), Map.entry("furnice", "furnace"), Map.entry("furnance", "furnace"),
        Map.entry("furnase", "furnace"), Map.entry("fernace", "furnace"), Map.entry("furnaced", "furnace"), 
        Map.entry("composer", "composter"), Map.entry("compost bin", "composter"), 
        Map.entry("storge", "storage"), Map.entry("storag", "storage"), Map.entry("strage", "storage"), Map.entry("dropoff", "drop off"), Map.entry("drop-off", "drop off"),
        Map.entry("stoked", "stocked"), Map.entry("stockd", "stocked"), Map.entry("stokced", "stocked"), Map.entry("stockt", "stocked"), Map.entry("restocked", "stocked"),
        Map.entry("cobble", "cobblestone"), Map.entry("cobbles", "cobblestone"), Map.entry("cobblestones", "cobblestone"), Map.entry("cobbel", "cobblestone"),
        Map.entry("stuff", "items"), Map.entry("things", "items"), Map.entry("loot", "items"), Map.entry("junk", "items"), Map.entry("gubbins", "items"),
        Map.entry("clobber", "items"), Map.entry("bits", "items"), Map.entry("iorn", "iron"), Map.entry("irn", "iron"), Map.entry("diamonds", "diamond"),
        Map.entry("dimonds", "diamond"), Map.entry("diamons", "diamond"), Map.entry("torchs", "torches"), Map.entry("torch", "torches"), Map.entry("tourches", "torches"),
        Map.entry("plank", "planks"),
        Map.entry("ingot", "ingots"), Map.entry("breads", "bread"), Map.entry("loaves", "bread"), Map.entry("loaf", "bread"),
        Map.entry("stak", "stack"), Map.entry("staks", "stacks"), Map.entry("stax", "stacks"), Map.entry("stck", "stack"), Map.entry("stcks", "stacks"),
        Map.entry("pls", ""), Map.entry("plz", ""), Map.entry("please", ""), Map.entry("kindly", ""), Map.entry("u", "you"), Map.entry("ur", "your"),
        Map.entry("im", "i am"), Map.entry("ive", "i have"), Map.entry("dont", "do not"), Map.entry("whats", "what is"), Map.entry("wheres", "where is"),
        Map.entry("gimme", "give me"), Map.entry("em", "them"), Map.entry("innit", ""), Map.entry("mate", ""), Map.entry("bruv", ""), Map.entry("buddy", ""),
        Map.entry("til", "till"), Map.entry("untill", "until"), Map.entry("untl", "until"), Map.entry("fuelled", "fueled"), Map.entry("refuelled", "fueled"),
        Map.entry("organise", "sort"), Map.entry("organize", "sort"), Map.entry("arrange", "sort"), Map.entry("sorted", "sort"), Map.entry("smelted", "smelt"),
        Map.entry("pinged", "this"), Map.entry("ingame", ""), Map.entry("hav", "have"), Map.entry("hve", "have"), Map.entry("teh", "the"), Map.entry("hte", "the"),
        Map.entry("tht", "that"), Map.entry("thsi", "this"), Map.entry("htis", "this"), Map.entry("thia", "this"), Map.entry("intp", "into"), Map.entry("inot", "into"));

    // ------------------------------------------------------------------ reading

    /** A sentence made plain for this reader: lower case, British turns opened out, number words as digits, its own typos put right. */
    public static String plain(String text) {
        String c = PaWords.british(PaWords.clean(text));
        c = (" " + c + " ").replace(" , ", " , ");
        StringBuilder b = new StringBuilder();
        String[] w = c.trim().split(" ");
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            if (x.isEmpty()) continue;
            String two = i + 1 < w.length ? x + " " + w[i + 1] : null;
            if (two != null && WORDS.containsKey(two)) { b.append(WORDS.get(two)).append(' '); i++; continue; }
            String y = WORDS.get(x);
            if (y == null) y = WORDS.get(x.replace("'", ""));
            if (y == null && x.endsWith("'s") && WORDS.containsKey(x.substring(0, x.length() - 2))) y = WORDS.get(x.substring(0, x.length() - 2));
            if (y == null && !x.contains("'")) y = near(x);
            b.append(y != null ? y : x.replace("'", "")).append(' ');
        }
        String s = PaWords.numbers(b.toString().replaceAll("\\s+", " ").trim());
        s = s.replaceAll("(?:\\s*,?\\s*\\b(?:ta|thanks|cheers|thank you|thx|ty|ta very much|mate|love|pal|bud|bro|innit|yeah|ok|okay|now|asap|cheers mate|ta mate|thanks mate)\\b)+\\s*$", "");
        s = s.replaceAll("[\\s,]+$", "").replaceAll("^[\\s,]+", "");
        s = s.replaceAll("\\b(?:in|into|inside) (?:it|there) (?:please )?$", "in there");
        s = s.replaceAll("\\bdrop ?off point\\b", "drop off").replaceAll("\\bdrop off chest\\b", "drop off");
        s = s.replaceAll("\\bin to\\b", "into").replaceAll("\\bout from\\b", "from").replaceAll("\\bout of\\b", "from");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static final String[] NEAR_WORDS = {"chest", "barrel", "shulker", "hopper", "furnace", "smoker", "composter", "dispenser", "dropper", "storage",
        "stocked", "cobblestone", "torches", "everything"};
    /** Real words a slip-fixer must leave alone. */
    private static final java.util.Set<String> REAL = java.util.Set.of("stock", "stocks", "stack", "stacks", "compost", "chess", "cheats", "smoke", "smokes",
        "hoped", "hopes", "stoke", "stocking", "chase", "chaste", "cheese", "store", "stores", "stored", "torch", "stacked", "check", "cheap", "barred");

    /** One of this reader's own words, misspelt: put right when it is close (two slips in a long word, one in a short). */
    private static String near(String w) {
        if (w.length() < 5 || w.chars().anyMatch(Character::isDigit) || REAL.contains(w)) return null;
        int most = w.length() <= 6 ? 1 : 2;
        for (String v : NEAR_WORDS) {
            if (v.equals(w)) return null;
            if (v.startsWith(w) || w.startsWith(v)) continue;
            if (Math.abs(v.length() - w.length()) <= most && w.charAt(0) == v.charAt(0) && PaWords.distance(w, v, most) <= most) return v;
        }
        return null;
    }

    /**
     * Read a sentence (as said; the assistant's name already off). Null when it isn't a logistics order: the reader
     * reads it the usual way. Otherwise the steps, in order.
     */
    public static List<PaIntent> read(String text) {
        String c = plain(text);
        c = c.replaceAll("^(?:hello|ok|right|so|now|also|just|and|alright|oi|hey|please|can you|could you|would you|will you|i want you to|i need you to|i would like you to|go and|go|be a dear and|do me a favour and|could you please|can you please)\\s+", "");
        c = c.replaceAll("^(?:can you|could you|would you|will you|please)\\s+", "").trim();
        if (c.isEmpty()) return null;
        String raw = text;
        // whole-sentence orders first: the ones whose "and" or "then" is part of the order
        PaLogiOrder whole = whole(c);
        if (whole != null) return List.of(intent(whole, raw));
        List<PaIntent> compound = compound(c, raw);
        if (compound != null) return compound;
        // cut into steps; if any step is ours, every step is read here (the plain ones by the usual reader)
        List<String> parts = PaUnderstand.split(c);
        if (parts.size() <= 1) {
            PaLogiOrder one = clause(c, null);
            return one == null ? null : List.of(intent(one, raw));
        }
        List<PaIntent> out = new ArrayList<>();
        boolean ours = false;
        String lastItem = "";
        PaIntent prev = null;
        for (String part : parts) {
            String p = part.trim().replaceAll("^(?:and|then|also|after that|and then) ", "").trim();
            if (p.isEmpty()) continue;
            PaLogiOrder o = clause(p, lastItem);
            if (o != null) {
                ours = true;
                PaIntent i = intent(o, raw);
                out.add(i);
                prev = i;
                if (!o.item().isEmpty() && !o.item().equals("it") && !o.item().equals("*")) lastItem = o.item();
                continue;
            }
            String pl = p.replaceAll("^(?:can you|could you|please|go and|go) ", "");
            PaIntent i = PaUnderstand.clause(pl, prev, raw);
            if (i.kind() == PaIntent.Kind.UNKNOWN) return ours ? null : null;
            out.add(i);
            prev = i;
            if (!i.item().isEmpty() && !i.item().equals("it")) lastItem = i.item();
        }
        return ours ? out : null;
    }

    static PaIntent intent(PaLogiOrder o, String raw) {
        return new PaIntent(PaIntent.Kind.LOGI, o.spec(), 0, o.to().pointed() || o.from().pointed() ? PaIntent.Place.LOOKED : PaIntent.Place.NONE, raw);
    }

    /** The orders read from a sentence, for the tests (null when none). */
    public static List<PaLogiOrder> orders(String text) {
        List<PaIntent> r = read(text);
        if (r == null) return null;
        List<PaLogiOrder> out = new ArrayList<>();
        for (PaIntent i : r) if (i.kind() == PaIntent.Kind.LOGI) out.add(PaLogiOrder.parse(i.item()));
        return out;
    }

    /** "FILL stone 100 > POINT ; ..." for the tests: every step, the plain ones as the reader's brief. */
    public static String brief(String text) {
        List<PaIntent> r = read(text);
        if (r == null) return "null";
        List<String> b = new ArrayList<>();
        for (PaIntent i : r) {
            if (i.kind() == PaIntent.Kind.LOGI) {
                PaLogiOrder o = PaLogiOrder.parse(i.item());
                b.add(o == null ? "?" : o.brief());
            } else b.add(i.brief());
        }
        return String.join(" ; ", b);
    }

    // ------------------------------------------------------------------ whole sentences

    private static final Pattern P_GATHER_PUT = Pattern.compile("^(?:go )?(" + GET + ")(?: down| up| out)? ((?:(?!\\bthen\\b|,|\\band\\b).)+?) (?:, ?)?(?:and then|and|then|n|,) (?:go )?(?:" + PUT + "|bring|give)(?: (?:it|them|those|that|all of it|it all|the lot|all|everything|em|the (?:\\w+ ?){1,3}))?(?: (?:all|back))? (?:back )?(?:in|into|inside|to|onto|in there|over to) (.+)$");
    private static final Pattern P_FILL_WITH = Pattern.compile("^(?:fill|stock|top up|load|pack|stuff|refill|fill up|top off)(?: up)? (.+?) (?:up )?with (.+)$");
    private static final Pattern P_MAKE_PUT = Pattern.compile("^(craft|make|cook|smelt|bake|build|forge|produce|whip up|knock up) ((?:(?!\\bthen\\b|,|\\band\\b).)+?) (?:, ?)?(?:and then|and|then|,) (?:" + PUT + "|bring|give)(?: (?:it|them|those|that|all|em|the (?:\\w+ ?){1,3}))? (?:in|into|inside|to|in there) (.+)$");
    private static final Pattern P_STOCK = Pattern.compile("^(?:keep|make sure|always keep|ensure)(?: that(?= (?:the|this|that|my|our|your|all|those|these)\\b))? (.+?) (?:is |are |stays |remains |always )*(?:always )?(?:stocked|topped up|full|filled|supplied|loaded|stacked|kept full|fully stocked|well stocked|restocked)(?: up)?(?: with| of| on)? ?(.*)$");
    private static final Pattern P_STOCK2 = Pattern.compile("^(?:keep|always keep|make sure there (?:is|are)(?: always)?|make sure there's(?: always)?|always have) (.+?) (?:in|inside) (.+?)(?: at all times| always| topped up| stocked)?$");
    private static final Pattern P_HAUL = Pattern.compile("^(?:haul|ferry|shuttle|cart|lug|run|carry|keep (?:hauling|bringing|moving|carrying|taking|ferrying|running)|move|bring|take|transport)(?: (.+?))? from (.+?) (?:to|into|back to|over to|and back to) (.+?)(?: (until i say stop|till i say stop|until i tell you to stop|till i tell you to stop|until stopped|until stop|on repeat|forever|all day|non ?stop|again and again|over and over|on a loop|in a loop|nonstop|continuously|constantly|repeatedly|until (?:it is|its|it's) (?:empty|done|all moved)|(\\d{1,3}) (?:times|trips|runs|loads)))?$");
    private static final Pattern P_WHEN_FULL = Pattern.compile("^(?:when|whenever|once|if) (?:your|ur|the) (?:pack|inventory|bag|backpack|pockets?)(?: is| gets| are)? (?:full|filled up|full up)(?: up)?,? (.*)$");
    private static final Pattern P_DEATH = Pattern.compile("^(?:fetch|get|grab|bring|collect|go get|pick up|recover|retrieve)(?: back)?(?: me)? (?:my|our) (?:items|gear|drops|inventory|belongings|everything|things|loot|kit)(?: back)? (?:from )?(?:where i died|where i fell|where i dropped (?:it|them)|from my death|from my grave|at my death spot|from my death spot|where i was killed)(?: (?:and|then) (?:put|drop|store|place|stash|bring) (?:it|them|everything|it all)(?: back)? (?:in|into|to) (.+))?$");

    /** Orders whose "and" belongs to the order. Null when none. */
    static PaLogiOrder whole(String c) {
        Matcher m;
        // "when your pack is full, go to the drop-off and come back"
        if ((m = P_WHEN_FULL.matcher(c)).matches()) {
            String rest = m.group(1);
            Ref r = rest.matches(".*\\b(?:drop off|storage|chest|stash|base|home)\\b.*") ? ref(rest.replaceAll("^.*?\\b(?:go to|use|empty (?:it )?(?:into|in)|drop (?:it )?(?:off )?(?:at|in)|dump (?:it )?(?:in|into|at)|put (?:it|them|everything) (?:in|into)|head to|take it to|bring it to|go back to)\\s*", "")
                .replaceAll("\\s*(?:and|then) (?:come back|carry on|keep going|get back to (?:it|work)|return)\\b.*$", "")) : Ref.of(Ref.Kind.DROPOFF);
            if (r.kind() == Ref.Kind.NONE) r = Ref.of(Ref.Kind.DROPOFF);
            return PaLogiOrder.of(Op.AUTODROP).withTo(r).withArg("on");
        }
        if ((m = P_DEATH.matcher(c)).matches()) {
            Ref to = m.group(1) == null ? Ref.of(Ref.Kind.ME) : ref(m.group(1));
            return PaLogiOrder.of(Op.DEATH).withItem("*").withQty(Qty.ALL).withTo(to.kind() == Ref.Kind.NONE ? Ref.of(Ref.Kind.ME) : to);
        }
        if ((m = P_HAUL.matcher(c)).matches() && !c.matches("^(?:move|bring|take|carry|transport) (?:everything|all|it all|the lot|all of it|items|all the items|.*) from (?:this|that|the|my) " + CONT + " (?:to|into) .*") || (m != null && m.matches() && c.matches(".*\\b(?:until i say stop|till i say stop|on repeat|forever|all day|non ?stop|over and over|on a loop|in a loop|repeatedly|times|trips|runs)\\b.*"))) {
            m = P_HAUL.matcher(c);
            if (m.matches()) {
                String what = m.group(1) == null ? "*" : item(m.group(1), "");
                Ref from = place(m.group(2)), to = place(m.group(3));
                if (from.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.NONE) {
                    String trips = m.group(5) != null ? m.group(5) : "";
                    return new PaLogiOrder(Op.HAUL, what.isEmpty() ? "*" : what, Qty.ALL, from, to, Src.ANY, trips.isEmpty() ? "loop" : trips);
                }
            }
        }
        // "take my diamonds and put them in the vault": its player's own
        if ((m = Pattern.compile("^(?:take|grab|have) (?:all )?(?:of )?my (.+?) and (?:" + PUT + ") (?:it|them|those|em|all of it|it all)? ?(?:in|into|inside|to) (.+)$").matcher(c)).matches()) {
            Ref to = ref(m.group(2));
            if (to.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.ME) {
                Qty q = PaQty.read(m.group(1));
                String it = item(PaQty.strip(m.group(1)), "");
                return new PaLogiOrder(Op.HANDOVER, it.isEmpty() ? "*" : it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.ME), to, Src.PLAYER, "");
            }
        }
        // "gather 100 stone and put it in this chest"
        if ((m = P_GATHER_PUT.matcher(c)).matches()) {
            String verb = m.group(1), what = m.group(2), where = m.group(3);
            Ref to = ref(where);
            if (to.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.ME) {
                Qty q = PaQty.read(what);
                String it = item(PaQty.strip(what), verb);
                if (it.isEmpty() && verb.matches("chop|cut|fell")) it = "logs";
                if (it.isEmpty() && verb.matches("mine|dig|quarry")) it = "stone";
                if (!it.isEmpty() && !it.equals("*") && !it.equals("it")) {
                    if (verb.matches("chop|cut|fell") && !it.contains("log")) it = it.matches("oak|birch|spruce|jungle|acacia|dark oak|mangrove|cherry") ? it + " log" : it;
                    // "until you have 32" is a count, not "until it's full"
                    return new PaLogiOrder(Op.FILL, it, q.said() ? q : Qty.FULL, Ref.NONE, to, verb.matches("craft|make") ? Src.CRAFT : Src.GATHER, "");
                }
            }
        }
        // "craft 64 torches and put them in the chest": made, then put away (handled as two steps by compound())
        // "fill this chest with cooked food", "stock the barrel with 2 stacks of bread"
        if ((m = P_FILL_WITH.matcher(c)).matches()) {
            Ref to = ref(m.group(1));
            if (to.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.ME) {
                String what = m.group(2);
                Qty q = PaQty.read(what);
                String it = item(PaQty.strip(what).replaceAll("\\b(?:until|till) (?:it'?s|it is) full\\b", "").trim(), "");
                if (it.isEmpty() && "composter".equals(to.type())) it = "compostables";
                if (it.isEmpty()) return null;
                if ("composter".equals(to.type())) return new PaLogiOrder(Op.COMPOST, it, q.said() ? q : Qty.FULL, Ref.NONE, to, Src.ANY, "");
                if (to.type().matches("furnace|smoker|blast furnace") && it.matches("fuel|coal|charcoal|logs|planks")) return new PaLogiOrder(Op.FUEL, it, q, Ref.NONE, to, Src.ANY, "");
                return new PaLogiOrder(Op.FILL, it, q.said() ? q : Qty.FULL, Ref.NONE, to, Src.ANY, "");
            }
        }
        // "fill the composter", "fill up this chest" (nothing said to fill it with: compost / what it's full of already)
        if (c.matches("^(?:fill|fill up|top up|refill)(?: up)? (?:the|that|this|my) composter(?: up)?$")) return new PaLogiOrder(Op.COMPOST, "compostables", Qty.FULL, Ref.NONE, Ref.of(Ref.Kind.DEF).withType("composter"), Src.ANY, "");
        // "keep this chest stocked with 64 bread", "keep the furnaces fuelled"
        if (c.matches("^(?:keep|make sure|always keep)(?: that(?= (?:the|this|that|my|our|your|all|those|these)\\b))? (?:the|my|all the|all|those|these|our|this|that) (?:furnace|smoker|blast furnace|furnace and smoker)s? (?:fueled|stoked|going|burning|lit|topped up with fuel|full of fuel|supplied with fuel|fed|running)(?: up)?(?: .*)?$")
            || c.matches("^(?:keep|make sure)(?: that)? (?:the )?(?:furnace|smoker)s? (?:are |is )?(?:fueled|going|burning|lit|running)(?: .*)?$")) {
            Ref to = c.matches(".*\\b(?:this|that) (?:furnace|smoker).*") ? Ref.of(Ref.Kind.POINT).withType("furnace") : Ref.of(Ref.Kind.KNOWN).withType("furnace");
            return new PaLogiOrder(Op.STOCK, "fuel", Qty.of(16), Ref.NONE, to, Src.ANY, "");
        }
        if ((m = P_STOCK.matcher(c)).matches() && !c.matches("^keep (?:going|at it|it up|up|close|watch|guard|still|quiet).*")) {
            Ref to = ref(m.group(1));
            if (to.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.ME) {
                String what = m.group(2).trim();
                Qty q = PaQty.read(what);
                String it = item(PaQty.strip(what), "");
                if (it.isEmpty() && to.type().matches("furnace|smoker|blast furnace")) it = "fuel";
                if (it.isEmpty()) it = "same";
                return new PaLogiOrder(Op.STOCK, it, q.said() ? q : Qty.stacks(1), Ref.NONE, to, Src.ANY, "");
            }
        }
        if ((m = P_STOCK2.matcher(c)).matches() && c.matches("^(?:keep|always keep|make sure|always have).*")) {
            Ref to = ref(m.group(2));
            if (to.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.ME && !m.group(1).matches(".*\\b(?:me|you|yourself|it)\\b.*")) {
                Qty q = PaQty.read(m.group(1));
                String it = item(PaQty.strip(m.group(1)), "");
                if (!it.isEmpty()) return new PaLogiOrder(Op.STOCK, it, q.said() ? q : Qty.stacks(1), Ref.NONE, to, Src.ANY, "");
            }
        }
        // "move everything from this chest to that chest", "empty this chest into that one"
        Matcher mv = Pattern.compile("^(?:move|transfer|shift|take|carry|bring|put|empty|clear out|tip|swap|relocate|dump|unload)(?: (.+?))? (?:from|out of) (.+?) (?:to|into|in|over to|across to|and put (?:it|them|everything) (?:in|into)) (.+)$").matcher(c);
        if (mv.matches()) {
            Ref from = ref(mv.group(2)), to = ref(mv.group(3));
            if (from.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.NONE && from.kind() != Ref.Kind.ME) {
                if (to.kind() == Ref.Kind.POINT && from.kind() == Ref.Kind.POINT) to = new Ref(Ref.Kind.POINT2, to.name(), to.type());
                String what = mv.group(1) == null ? "*" : mv.group(1);
                Qty q = PaQty.read(what);
                String it = item(PaQty.strip(what), "");
                if (it.isEmpty() || it.equals("it")) it = "*";
                return new PaLogiOrder(Op.MOVE, it, q.said() ? q : Qty.ALL, from, to, Src.ANY, "");
            }
        }
        Matcher em = Pattern.compile("^(?:empty|clear out|clear|unload|tip out|dump out|empty out) (.+?) (?:into|in|to|onto|over to) (.+)$").matcher(c);
        if (em.matches()) {
            Ref from = ref(em.group(1)), to = ref(em.group(2));
            if (from.kind() == Ref.Kind.PACK && to.kind() != Ref.Kind.NONE) return new PaLogiOrder(Op.EMPTY_PACK, "*", Qty.ALL, Ref.of(Ref.Kind.PACK), to, Src.PACK, "");
            if (from.kind() != Ref.Kind.NONE && to.kind() != Ref.Kind.NONE && from.kind() != Ref.Kind.ME) {
                if (to.kind() == Ref.Kind.POINT && from.kind() == Ref.Kind.POINT) to = new Ref(Ref.Kind.POINT2, to.name(), to.type());
                return new PaLogiOrder(Op.MOVE, "*", Qty.ALL, from, to, Src.ANY, "");
            }
        }
        return null;
    }

    /** Two orders that read as one sentence, each a step of its own: "craft 64 torches and put them in the chest". */
    static List<PaIntent> compound(String c, String raw) {
        Matcher m = P_MAKE_PUT.matcher(c);
        if (m.matches()) {
            Ref to = ref(m.group(3));
            if (to.kind() != Ref.Kind.NONE) {
                String verb = m.group(1), what = m.group(2);
                PaIntent made = PaUnderstand.clause(verb + " " + what, null, raw);
                if (made.kind() == PaIntent.Kind.CRAFT || made.kind() == PaIntent.Kind.SMELT) {
                    Qty q = PaQty.read(what);
                    String it = made.kind() == PaIntent.Kind.SMELT ? smelted(made.item()) : made.item();
                    PaLogiOrder put = to.kind() == Ref.Kind.ME ? null
                        : new PaLogiOrder(Op.DEPOSIT, it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.PACK), to, Src.PACK, "made");
                    if (put == null) return null;
                    return List.of(made, intent(put, raw));
                }
            }
        }
        return null;
    }

    /** What smelting a thing gives, as a word: "iron" -> "iron ingot", "beef" -> "cooked beef", "sand" -> "glass". */
    static String smelted(String what) {
        return switch (what) {
            case "iron", "gold", "copper" -> what + " ingot";
            case "beef" -> "cooked beef";
            case "porkchop" -> "cooked porkchop";
            case "chicken", "mutton", "cod", "salmon" -> "cooked " + what;
            case "fish" -> "cooked fish";
            case "food" -> "cooked food";
            case "sand" -> "glass";
            case "logs" -> "charcoal";
            case "cobblestone" -> "stone";
            case "potato" -> "baked potato";
            default -> what;
        };
    }

    // ------------------------------------------------------------------ one step

    /** One step that is ours, or null. {@code last} is the thing spoken of before ("smelt it, then put the ingots away"). */
    static PaLogiOrder clause(String c, String last) {
        c = c.trim();
        if (c.isEmpty()) return null;
        Matcher m;
        PaLogiOrder w = whole(c);
        if (w != null) return w;

        // ---- questions: counts and finds
        if ((m = Pattern.compile("^(?:how (?:much|many)) (.+?)(?: (?:do|have|did|does|are|is)(?: we| i| you| there)?(?: have| got| own| store| stored| left| kept| in (.+?))?(?: in (.+?))?(?: altogether| in total| total| between us| all together| overall)?)?$").matcher(c)).matches()) {
            String thing = m.group(1).replaceAll("\\b(?:do|have|did|are|is|we|i|you|there|got|left|in total|altogether)\\b.*$", "").trim();
            String it = item(thing, "");
            if (it.isEmpty() || it.equals("*") || it.equals("it")) return null;
            String where = m.group(2) != null ? m.group(2) : m.group(3);
            Ref r = where == null ? Ref.of(Ref.Kind.KNOWN) : ref(where);
            if (r.kind() == Ref.Kind.NONE) r = Ref.of(Ref.Kind.KNOWN);
            return new PaLogiOrder(Op.COUNT, it, Qty.NONE, r, Ref.NONE, Src.ANY, "");
        }
        if ((m = Pattern.compile("^(?:do|have) (?:we|i|you) (?:have|got) (?:any|some|enough)? ?(.+?)(?: (?:left|in storage|in (?:the|my|our) " + CONT + "|stored|anywhere))?$").matcher(c)).matches()
            && !m.group(1).matches(".*\\b(?:time|idea|clue|plan|home|bed|house|pickaxe|axe|sword|food)\\b.*")) {
            String it = item(m.group(1), "");
            if (!it.isEmpty() && !it.equals("*") && !it.equals("it") && c.matches(".*\\b(?:storage|chest|stash|left|stored|anywhere|we)\\b.*"))
                return new PaLogiOrder(Op.COUNT, it, Qty.NONE, Ref.of(Ref.Kind.KNOWN), Ref.NONE, Src.ANY, "");
        }
        if ((m = Pattern.compile("^(?:count|tally|total up|add up)(?: up)? (?:all )?(?:the |my |our |your )?(.+?)(?: in (.+))?$").matcher(c)).matches()) {
            String it = item(m.group(1), "");
            if (!it.isEmpty() && !it.equals("it")) return new PaLogiOrder(Op.COUNT, it, Qty.NONE, m.group(2) == null ? Ref.of(Ref.Kind.KNOWN) : ref(m.group(2)), Ref.NONE, Src.ANY, "");
        }
        if ((m = Pattern.compile("^what (?:is|have we got|have i got|do we have|do i have|have you got|is there|s) (?:in|inside) (.+?)$").matcher(c)).matches()) {
            Ref r = ref(m.group(1));
            if (r.kind() == Ref.Kind.STORAGE || r.kind() == Ref.Kind.KNOWN) return PaLogiOrder.of(Op.LIST);
            if (r.kind() != Ref.Kind.NONE && r.kind() != Ref.Kind.PACK && r.kind() != Ref.Kind.ME) return new PaLogiOrder(Op.COUNT, "*", Qty.NONE, r, Ref.NONE, Src.ANY, "");
        }
        if ((m = Pattern.compile("^(?:find|ping|show|point (?:me )?(?:to|at)|locate|which is|where is|where s|wheres|which)(?: me)? (?:a |the |which |my )?(?:chest|barrel|container|box|shulker|one) (?:with|that has|that have|has|have|holding|containing|which has|full of|with the|that s got|thats got|that is got|with some|that contains|got) (?:any |some |the |my )?(.+?)(?: in it| in them| inside)?$").matcher(c)).matches()
            || (m = Pattern.compile("^which (?:chest|barrel|container|box|one) (?:has|have|is|s|holds|contains|did i put|did we put|did you put) (?:the |my |any |some )?(.+?)(?: in it| in| in them)?$").matcher(c)).matches()
            || (m = Pattern.compile("^where (?:is|are|s|did i put|did we put|did you put|do we keep|do i keep|do you keep|are we keeping|have we got|have i put) (?:the |my |our |all the |all my |all our |your )?(.+?)(?: kept| stored| stashed| at| put| now)?$").matcher(c)).matches()
            || (m = Pattern.compile("^(?:find|look for|search for|hunt for|check for) (?:my |the |our |some |any )?(.+?) (?:in|among|across|through) (?:my|the|our|your) (?:chest|storage|stash|barrel|container)s?$").matcher(c)).matches()) {
            String thing = m.group(1).trim();
            if (thing.matches("(?:you|it|that|this|your bed|my bed|bed|my house|home|base|the base|my base|my home|spawn|i died|i was|we are|you going|the mine|it at|my death|the village|the town|.*\\bi\\b.*)")) return null;
            String it = item(thing, "");
            if (it.isEmpty() || it.equals("*") || it.equals("it")) return null;
            if (c.startsWith("where") && !knownItemWord(it, thing)) return null;
            return new PaLogiOrder(Op.FIND, it, Qty.NONE, Ref.of(Ref.Kind.KNOWN), Ref.NONE, Src.ANY, "");
        }

        // ---- the storage map and a container's own settings
        if (c.matches("^(?:show|open|list|what are)(?: me)? (?:my |the |our |your |all )?(?:storage(?: map| view| list)?|chests?(?: list| map)?|known chests?|stash(?: map)?|containers?|barrels?)(?: you know| you know about| we have)?$")
            || c.matches("^(?:what|which) (?:chests?|containers?|storage|barrels?) do (?:you|we) know(?: about)?$") || c.equals("storage map") || c.equals("storage view")
            || c.matches("^what (?:is|s) in (?:my |the |our )?(?:storage|chest|stash)$"))
            return PaLogiOrder.of(Op.LIST);
        if ((m = Pattern.compile("^(?:make|set|use|mark|designate|have)(?: up)? (.+?) (?:as )?(?:the |my |your |our |a )?(?:drop off|dump(?:ing)? (?:spot|chest|point)|unloading (?:spot|chest)|offload(?:ing)? (?:spot|chest))$").matcher(c)).matches()
            || (m = Pattern.compile("^(.+?) (?:is|will be|s) (?:the |my |your |our )?(?:new )?(?:drop off|dump(?:ing)? chest|unloading chest)$").matcher(c)).matches()
            || (m = Pattern.compile("^(?:drop (?:it |things |stuff |items )?off|dump (?:it |things |stuff )?|unload) (?:here|in (?:this|that) (?:\\w+ )?" + CONT + ") (?:from now on|when (?:you'?re|your|you are) full|in future|when full)$").matcher(c)).matches()) {
            Ref r = m.groupCount() >= 1 && m.group(1) != null ? ref(m.group(1)) : Ref.of(Ref.Kind.POINT);
            if (r.kind() == Ref.Kind.NONE) r = Ref.of(Ref.Kind.POINT);
            return PaLogiOrder.of(Op.DROPOFF).withTo(r);
        }
        if (c.matches("^(?:stop using|stop|turn off|no more|forget) (?:the |your )?(?:auto(?:matic)? )?drop ?offs?(?: trips)?$") || c.matches("^(?:do not|dont) (?:go to|use) the drop off(?: any more| anymore)?$"))
            return PaLogiOrder.of(Op.AUTODROP).withArg("off");
        if (c.matches("^(?:turn on|use|start using) (?:the |your )?(?:auto(?:matic)? )?drop ?offs?(?: trips)?$")) return PaLogiOrder.of(Op.AUTODROP).withTo(Ref.of(Ref.Kind.DROPOFF)).withArg("on");
        if ((m = Pattern.compile("^(?:call|name|label|rename|tag|title|mark) ((?:this|that|the|my|our)(?: [a-z]+)?? " + CONT + "|this|that|it|this one|that one) (?:as |to |the |it )*(.+?)$").matcher(c)).matches()) {
            Ref r = ref(m.group(1));
            String name = cleanName(m.group(2));
            if (r.kind() != Ref.Kind.NONE && !name.isEmpty()) return PaLogiOrder.of(Op.NAME).withTo(r).withArg(name);
        }
        if ((m = Pattern.compile("^(this|that|this one|that one|here|this " + CONT + "|that " + CONT + ") (?:is|will be|s|shall be|can be) (?:the |my |our |your )?(.+?)(?: chest| barrel| box| storage| one)?$").matcher(c)).matches()) {
            String name = cleanName(m.group(2));
            if (name.matches("storage|stash|main storage|main chest|store|stores|store room")) return PaLogiOrder.of(Op.NAME).withTo(Ref.of(Ref.Kind.POINT)).withArg("storage");
            if (!name.isEmpty() && !name.matches("(?:bed|spawn|home|house|base|yours|mine|fine|good|ok|okay|done|it|full|empty|a chest|chest)") && c.matches(".*\\b(?:chest|barrel|box|storage|stash|shulker|" + "one)\\b.*"))
                return PaLogiOrder.of(Op.NAME).withTo(ref(m.group(1))).withArg(name);
        }
        if ((m = Pattern.compile("^(?:forget|stop using|do not use|dont use|never use|leave|ignore|stay out of|keep out of|stop touching|forget about) (.+?)(?: alone| from now on| any more| anymore| again)?$").matcher(c)).matches() && HAS_CONT.matcher(m.group(1)).find()) {
            Ref r = ref(m.group(1));
            if (r.kind() != Ref.Kind.NONE && r.kind() != Ref.Kind.DROPOFF) return PaLogiOrder.of(Op.FORGET).withTo(r);
        }
        if (c.matches("^(?:that|this|that one|this one|that " + CONT + "|this " + CONT + ")(?: is| s)? (?:not|isnt|is not|aint) (?:mine|my " + CONT + "|ours|yours to use)$"))
            return PaLogiOrder.of(Op.FORGET).withTo(Ref.of(Ref.Kind.POINT));
        if ((m = Pattern.compile("^(?:stop|quit|no more|do not keep|dont keep|stop keeping|stop stocking|stop restocking|stop filling|cancel (?:the )?(?:stock|restock)(?:ing)?(?: job)?(?: for)?)(?: (?:the|that|this) (?:stock|restock)(?:ing)?)? ?(.*?)(?: (?:stocked|topped up|filled|full|supplied|fueled))?(?: with .+)?$").matcher(c)).matches()
            && c.matches("^(?:stop|quit|no more|do not keep|dont keep|cancel).*(?:stock|topped up|top up|fueled|fuel|restock|filling|full).*") && !c.matches("^stop$")) {
            String rest = m.group(1) == null ? "" : m.group(1).replaceAll("^(?:keeping|stocking|restocking|filling|topping up|fueling)\\s*", "");
            Ref r = rest.isEmpty() ? Ref.of(Ref.Kind.KNOWN) : ref(rest);
            if (rest.matches(".*\\bfurnace.*") || c.contains("fuel")) r = r.kind() == Ref.Kind.NONE || r.kind() == Ref.Kind.KNOWN ? Ref.of(Ref.Kind.KNOWN).withType("furnace") : r;
            if (r.kind() == Ref.Kind.NONE) r = Ref.of(Ref.Kind.KNOWN);
            return PaLogiOrder.of(Op.UNSTOCK).withTo(r);
        }

        // ---- sorting
        if (c.matches("^(?:sort|tidy|tidy up|clean up|reorganise|rearrange|order|group|categorise|categorize)(?: out| up)? (?:all )?(?:of )?(?:the |my |our |your |this |that |these |those )?(?:" + CONT + "s?|storage|stash|stuff in (?:the|my|this|that) " + CONT + ")(?: (?:out|up|by (?:type|kind|category)|properly|for me|please|by what (?:s|is) in them))*$")) {
            Ref r = c.matches(".*\\b(?:this|that|these|those)\\b.*") ? Ref.of(Ref.Kind.POINT) : Ref.of(Ref.Kind.KNOWN);
            return new PaLogiOrder(Op.SORT, "*", Qty.NONE, Ref.NONE, r, Src.ANY, "");
        }

        // ---- furnaces, composters
        if ((m = Pattern.compile("^(?:fuel|refuel|stoke|feed|top up|light|fire up|put (?:some )?(?:fuel|coal|charcoal) (?:in|into))(?: up)? (.+?)(?: with (.+))?$").matcher(c)).matches() && c.matches(".*\\b(?:furnace|smoker|blast furnace)s?\\b.*")) {
            Ref r = ref(m.group(1));
            if (r.kind() == Ref.Kind.NONE) r = Ref.of(Ref.Kind.DEF).withType("furnace");
            String it = m.group(2) == null ? (c.matches(".*\\b(?:coal|charcoal)\\b.*") ? (c.contains("charcoal") ? "charcoal" : "coal") : "fuel") : item(m.group(2), "");
            Qty fq = PaQty.read(c);
            return new PaLogiOrder(Op.FUEL, it.isEmpty() ? "fuel" : it, fq.mode() == PaQty.Mode.EXACT || fq.mode() == PaQty.Mode.STACKS || fq.mode() == PaQty.Mode.SOME ? fq : Qty.NONE, Ref.NONE, r, Src.ANY, "");
        }
        if ((m = Pattern.compile("^(?:smelt|cook|melt|burn|bake|roast|fire|process|refine) (?:all |every ?thing |all the items |the lot |all of the items |the items |the stuff |what s |what is |whatever is |whatevers )?(?:in|inside|from) (.+?)(?: (?:in|at|using|with) (.+))?$").matcher(c)).matches()) {
            Ref from = ref(m.group(1));
            if (from.kind() != Ref.Kind.NONE && from.kind() != Ref.Kind.ME) {
                Ref at = m.group(2) == null ? Ref.NONE : ref(m.group(2));
                return new PaLogiOrder(Op.SMELT, "*", Qty.ALL, from, at.kind() == Ref.Kind.NONE ? Ref.of(Ref.Kind.NEAREST).withType("furnace") : at, Src.ANY, "");
            }
        }
        if ((m = Pattern.compile("^(?:smelt|cook|melt|burn|bake|roast|process|refine) (.+?) (?:in|at|using|with|on) (.+)$").matcher(c)).matches()) {
            Ref at = ref(m.group(2));
            if (at.kind() != Ref.Kind.NONE && at.type().matches("furnace|smoker|blast furnace|")) {
                String what = m.group(1);
                Qty q = PaQty.read(what);
                String it = item(PaQty.strip(what), "");
                boolean mine = what.matches(".*\\bmy\\b.*") && !what.matches(".*\\byour\\b.*");
                if (it.isEmpty() || it.equals("*")) it = "ore";
                return new PaLogiOrder(Op.SMELT, it, q.said() ? q : Qty.ALL, Ref.of(mine ? Ref.Kind.ME : Ref.Kind.PACK), at.kind() == Ref.Kind.DEF && at.type().isEmpty() ? at.withType("furnace") : at, mine ? Src.PLAYER : Src.PACK, "");
            }
        }
        if (c.matches("^(?:collect|take|get|grab|empty|clear|unload|fetch|pull)(?: out| me)? (?:the )?(?:output|ingots|results?|smelted (?:items|stuff|ore)|cooked (?:food|stuff|items)|finished (?:items|stuff)|stuff|items|everything|glass|charcoal)?(?: out)? ?(?:from|of|in)? ?(?:the|that|this|my|all the|those|these) (?:furnace|smoker|blast furnace)s?(?: out)?$")
            || c.matches("^(?:empty|clear|unload) (?:the|that|this|my|all the) (?:furnace|smoker|blast furnace)s?(?: out)?$")) {
            Ref r = c.matches(".*\\b(?:this|that)\\b.*") ? Ref.of(Ref.Kind.POINT).withType("furnace") : c.matches(".*\\b(?:all the|furnaces|smokers)\\b.*") ? Ref.of(Ref.Kind.KNOWN).withType("furnace") : Ref.of(Ref.Kind.DEF).withType("furnace");
            return new PaLogiOrder(Op.COLLECT, "*", Qty.ALL, r, Ref.NONE, Src.ANY, "");
        }
        if ((m = Pattern.compile("^(?:compost|put|throw|chuck|toss|stick|fill|feed) (.+?) (?:in|into|to) (?:the|that|this|my|a) composter$").matcher(c)).matches()
            || (m = Pattern.compile("^compost (?:all )?(?:the |my |your |some )?(.+)$").matcher(c)).matches()) {
            String it = item(m.group(1), "");
            if (it.isEmpty() || it.equals("*") || it.equals("it")) it = "compostables";
            return new PaLogiOrder(Op.COMPOST, it, PaQty.read(m.group(1)).said() ? PaQty.read(m.group(1)) : Qty.FULL, Ref.NONE, Ref.of(c.matches(".*\\b(?:this|that) composter\\b.*") ? Ref.Kind.POINT : Ref.Kind.DEF).withType("composter"), Src.ANY, "");
        }

        // ---- fetching for its player, taking into its pack
        if ((m = Pattern.compile("^(?:bring|get|fetch|grab|find|take|give|hand|pass|nab|pinch)(?: me| us| over)? (.+?) (?:from|in|out of|off) (.+)$").matcher(c)).matches()
            && (c.matches("^\\w+ (?:me|us) .*") || m.group(1).matches(".*\\bmy\\b.*") || m.group(2).matches(".*\\b(?:my|our) (?:chest|storage|stash|barrel)s?.*") || c.matches("^(?:bring|give|hand|pass) .*"))) {
            Ref from = ref(m.group(2));
            if (from.kind() != Ref.Kind.NONE && from.kind() != Ref.Kind.ME && from.kind() != Ref.Kind.PACK) {
                Qty q = PaQty.read(m.group(1));
                String it = item(PaQty.strip(m.group(1)), "");
                if (it.isEmpty()) it = "*";
                return new PaLogiOrder(Op.FETCH, it, q.said() ? q : (it.equals("*") ? Qty.ALL : Qty.SOME), from, Ref.of(Ref.Kind.ME), Src.KNOWN, "");
            }
        }
        if ((m = Pattern.compile("^(?:take|get|grab|fetch|withdraw|pull|remove|collect|pick up|load up(?: on)?|stock up on|pack)(?: out)? (.+?) (?:from|out of|in|off) (.+)$").matcher(c)).matches()) {
            Ref from = ref(m.group(2));
            if (from.kind() != Ref.Kind.NONE && from.kind() != Ref.Kind.ME && from.kind() != Ref.Kind.PACK) {
                Qty q = PaQty.read(m.group(1));
                String it = item(PaQty.strip(m.group(1)), "");
                if (it.isEmpty()) it = "*";
                return new PaLogiOrder(Op.TAKE, it, q.said() ? q : (it.equals("*") ? Qty.ALL : Qty.SOME), from, Ref.of(Ref.Kind.PACK), Src.KNOWN, "");
            }
        }

        // ---- emptying its pack
        if ((m = Pattern.compile("^(?:empty|unload|dump|clear out|offload|unpack|tip out|drop off|deposit) (?:all )?(?:of )?(?:your|ur|the|its) (?:pack|inventory|bag|backpack|pockets|items|load|haul|stuff|inventory out)(?: out)?(?: (?:in|into|at|to|onto|in to) (.+))?$").matcher(c)).matches()) {
            Ref to = m.group(1) == null ? Ref.of(Ref.Kind.STORAGE) : ref(m.group(1));
            return new PaLogiOrder(Op.EMPTY_PACK, "*", Qty.ALL, Ref.of(Ref.Kind.PACK), to.kind() == Ref.Kind.NONE ? Ref.of(Ref.Kind.STORAGE) : to, Src.PACK, "");
        }
        if (c.matches("^(?:empty|unload|offload)(?: up| out)?(?: everything)?$") || c.matches("^(?:drop (?:it|everything|them|it all|your load|the load) off|go (?:and )?(?:unload|empty out|drop off)(?: at the drop off)?)$"))
            return new PaLogiOrder(Op.EMPTY_PACK, "*", Qty.ALL, Ref.of(Ref.Kind.PACK), c.contains("drop") ? Ref.of(Ref.Kind.DROPOFF) : Ref.of(Ref.Kind.STORAGE), Src.PACK, "");

        // ---- putting things in: its pack's, or its player's ("put all my cobble in that chest")
        if ((m = Pattern.compile("^(?:" + PUT + "|give|hand|feed|add|deposit)(?: (?:all|some|it|them|those|em|everything|the|my|your|of|the rest of|the rest|any|a|an|\\d+|[\\w']+)){0,7}? (?:in|into|inside|to|onto|on|in there|in here|over to|back in|back into|away in)(?: .*)?$").matcher(c)).matches()
            || c.matches("^(?:" + PUT + "|store|stash|deposit|tidy|file) (.+?) away(?: (?:in|into) .+)?$")) {
            String verb = c.split(" ")[0];
            String body = c.substring(verb.length()).trim();
            String what, where;
            Matcher at = Pattern.compile("^(.*?)\\s*\\b(?:in|into|inside|onto|on|over to|back in|back into|away in|to)\\b\\s*(.*)$").matcher(body);
            if (body.matches("^(.+?) away(?: (?:in|into) .+)?$")) {
                what = body.replaceAll(" away.*$", "");
                where = body.matches(".* away (?:in|into) .+") ? body.replaceAll("^.* away (?:in|into) ", "") : "storage";
            } else if (at.matches()) {
                what = at.group(1);
                where = at.group(2);
                if (where.isEmpty()) return null;
                if (where.matches("^(?:there|here)$")) where = "there";
                if (body.matches(".*\\bin (?:there|here)$") && what.isEmpty()) what = "it";
            } else return null;
            Ref to = ref(where);
            if (to.kind() == Ref.Kind.NONE || to.kind() == Ref.Kind.ME || to.kind() == Ref.Kind.PACK) return null;
            if (verb.matches("throw|toss|chuck") && to.kind() == Ref.Kind.POINT && !HAS_CONT.matcher(where).find() && !where.matches("there|here|in there|it")) return null;
            if (what.isBlank() && verb.matches("unload|empty|dump|offload|tip")) what = "everything";
            boolean players = what.matches("(?:.* )?(?:my|our|mine) .*|^(?:all )?mine$") && !what.matches(".*\\byour\\b.*");
            Qty q = PaQty.read(what);
            String it = item(PaQty.strip(what), "");
            if (it.isEmpty() && what.matches("(?:.*\\b)?(?:all|everything|the lot|it all|all of it)\\b.*")) it = "*";
            if (it.isEmpty()) it = what.isBlank() ? "it" : "it";
            if (it.equals("ingots") && last != null && last.matches("iron|gold|copper")) it = last + " ingot";
            if (it.equals("it") && last != null && !last.isEmpty()) it = last;
            if ("composter".equals(to.type())) return new PaLogiOrder(Op.COMPOST, it.equals("*") || it.equals("it") ? "compostables" : it, q.said() ? q : Qty.FULL, Ref.NONE, to, Src.ANY, "");
            if (to.type().matches("furnace|smoker|blast furnace") && it.matches("fuel|coal|charcoal|logs|planks|coal block")) return new PaLogiOrder(Op.FUEL, it, q, Ref.NONE, to, Src.ANY, "");
            if (to.type().matches("furnace|smoker|blast furnace")) return new PaLogiOrder(Op.SMELT, it.equals("*") ? "ore" : it, q.said() ? q : Qty.ALL, Ref.of(players ? Ref.Kind.ME : Ref.Kind.PACK), to, players ? Src.PLAYER : Src.PACK, "");
            if (players) return new PaLogiOrder(Op.HANDOVER, it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.ME), to, Src.PLAYER, "");
            if (it.equals("*") && to.kind() != Ref.Kind.POINT && to.kind() != Ref.Kind.DEF && to.kind() != Ref.Kind.NAMED && to.kind() != Ref.Kind.POINT2)
                return new PaLogiOrder(Op.EMPTY_PACK, "*", Qty.ALL, Ref.of(Ref.Kind.PACK), to, Src.PACK, "");
            return new PaLogiOrder(Op.DEPOSIT, it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.PACK), to, Src.PACK, "");
        }
        // "store the cobblestone", "stash the iron", "deposit the logs": into storage
        if ((m = Pattern.compile("^(?:store|stash|deposit|bank|file away|squirrel away) (?:all )?(?:of )?(?:the |your |that |those |this |these |my )?(.+?)$").matcher(c)).matches() && !c.matches("^deposit$")) {
            String what = m.group(1);
            boolean players = c.matches(".*\\bmy\\b.*");
            Qty q = PaQty.read(what);
            String it = item(PaQty.strip(what), "");
            if (it.isEmpty() || it.equals("it")) it = it.isEmpty() ? "*" : (last != null && !last.isEmpty() ? last : it);
            if (players) return new PaLogiOrder(Op.HANDOVER, it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.ME), Ref.of(Ref.Kind.STORAGE), Src.PLAYER, "");
            if (it.equals("*")) return new PaLogiOrder(Op.EMPTY_PACK, "*", Qty.ALL, Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.STORAGE), Src.PACK, "");
            return new PaLogiOrder(Op.DEPOSIT, it, q.said() ? q : Qty.ALL, Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.STORAGE), Src.PACK, "");
        }
        return null;
    }

    private static String cleanName(String s) {
        String n = s.replaceAll("^(?:the|my|our|your|a|an) ", "").replaceAll(" (?:chest|barrel|box|one|storage|container|please|for me)$", "").replaceAll("[^a-z0-9 ]", "").trim();
        if (n.length() > 24) n = n.substring(0, 24).trim();
        return n.matches("(?:it|this|that|chest|barrel|box)") ? "" : n;
    }

    // ------------------------------------------------------------------ places

    private static final Pattern P_NEAR = Pattern.compile("^(?:the |a |my |our )?(.*?)(?:chest|barrel|box|container|storage|shulker|crate) (?:by|near|next to|beside|besides|close to|nearest|nearest to|closest to|at|under|over|behind|in front of|on the left of|on the right of|left of|right of|opposite|across from) (?:the |my |our |your |a )?(.+)$");

    /** A place a step names ("this chest", "the iron chest", "my storage", "the chest by the furnace"). NONE when it names none. */
    static Ref ref(String s) {
        if (s == null) return Ref.NONE;
        String p = s.trim().replaceAll("^(?:in|into|inside|to|onto|on|at|from|out of|over to|back in|back into)\\s+", "")
            .replaceAll("\\s+(?:please|for me|now|right now|thanks|cheers|then|too|as well|again|and come back|and then come back|asap|when you can|for later|quickly|instead|first)$", "").trim();
        p = p.replaceAll("\\s+(?:please|for me|now|then|too|as well)$", "").trim();
        if (p.isEmpty()) return Ref.NONE;
        String type = type(p);
        if (p.matches("^(?:there|in there|in here|here|this|that|this one|that one|it|that spot|this spot|the one i am looking at|the one i m looking at|the one i pinged|what i am looking at|where i am looking|where i m looking|the pinged one|the marked one)$"))
            return p.equals("it") ? Ref.of(Ref.Kind.IT) : Ref.of(Ref.Kind.POINT);
        if (p.matches("^(?:me|us|my hands?|my inventory|myself|my hotbar)$")) return Ref.of(Ref.Kind.ME);
        if (p.matches("^(?:your|ur|its|the)? ?(?:pack|inventory|backpack|bag|pockets?)$")) return Ref.of(Ref.Kind.PACK);
        if (p.matches("^(?:my |the |our |your )?ender ?chest$")) return Ref.of(Ref.Kind.ENDER).withType("ender chest");
        if (p.matches("^(?:the |my |our |your |a |the main |our main |my main )?(?:drop off|dump|dumping spot|dumping chest|unloading chest|offload chest)(?: " + CONT + ")?$")) return Ref.of(Ref.Kind.DROPOFF);
        if (p.matches("^(?:my |our |the |your |the main |my main |our main |base |the base |home |the home )?(?:storage|stash|stores|store room|storeroom|storage room|storage area|storage system|warehouse|vault|stockpile|chest room|main chest|storage chest)s?(?: (?:at|in) (?:the )?(?:base|home|my house))?$"))
            return Ref.of(Ref.Kind.STORAGE);
        if (p.matches("^(?:my|our|your|all my|all our|all the|the|all of my|all of the|any of my|any of the|one of my|one of the|the base|base) (?:chest|barrel|container|box|shulker)s(?: at (?:home|base))?$")
            || p.matches("^(?:my|our|all my|all our|all of my|any of my|one of my) (?:chest|barrel|container|box|shulker|storage)(?: at (?:home|base))?$") && s.matches(".*\\b(?:chests|barrels|containers|boxes|all|any|one of)\\b.*"))
            return Ref.of(Ref.Kind.KNOWN).withType(type);
        if (p.matches("^(?:my|our) (?:chest|barrel|box|container)$")) return Ref.of(Ref.Kind.STORAGE).withType(type);
        if (p.matches("^(?:the |a |any |an )?(?:nearest|closest|nearby|next|empty|spare|free|any|other|another|an empty|a spare|a free)(?: empty| spare)? (?:" + CONT + ")$") || p.matches("^(?:a|an|any|some) " + CONT + "$"))
            return Ref.of(p.matches(".*\\b(?:next|other|another)\\b.*") ? Ref.Kind.NEAREST : Ref.Kind.NEAREST).withType(type);
        if (p.matches("^(?:home|base|the base|my base|our base|the house|my house|our house|my home|our home|the home|back home|back to base|camp|the camp)$")) return Ref.of(Ref.Kind.HOME);
        Matcher near = P_NEAR.matcher(p);
        if (near.matches()) {
            String lm = near.group(2).replaceAll("^(?:the|my|our|a) ", "").trim();
            if (lm.matches("(?:furnace|furnaces|crafting table|table|door|front door|bed|my bed|the bed|stairs|ladder|anvil|smoker|enchanting table|brewing stand|well|farm|field|gate|fence|wall|window|torch|lamp|fire|campfire|house|base|home|mine|portal|nether portal|tree|path|bridge|pen|tower|me|you|entrance|exit|lectern|bookshelf|bookshelves|stonecutter|smithing table|loom|barrel|chest|cauldron|composter|hopper|beacon|bell|banner|sign|kitchen|bedroom|workshop|forge|smeltery|the [a-z]+)"))
                return new Ref(Ref.Kind.NEAR, lm, type(p.substring(0, p.length() - near.group(2).length())));
        }
        boolean every = p.matches("^(?:all|all of|every|each) .*");
        if (every) p = p.replaceAll("^(?:all of|all|every|each) ", "").replaceAll("^(?!the |my |our |your |this |that |these |those )", "the ");
        Matcher pt = Pattern.compile("^(this|that|these|those|the|my|our|your|a|an)(?: (.+?))? (" + CONT + ")s?(?: (?:one|here|there|over there|right here|i m looking at|i am looking at|i pinged|you can see))?$").matcher(p);
        if (pt.matches()) {
            String det = pt.group(1), adj = pt.group(2) == null ? "" : pt.group(2).trim();
            adj = adj.replaceAll("^(?:big|small|little|new|old|left|right|other|second|first|main|same|nearest|closest)\\s*", "").trim();
            if (adj.matches("(?:big|small|little|new|old|other|same|double|single|wooden|oak|spruce|this|that|shulker|trapped|ender|blast|brewing|all the|all|all of the|all my|all our|my|our)")) adj = "";
            boolean plural = pt.group(3).endsWith("s") && !pt.group(3).matches("(?:storage|stash|drop off|box)") || pt.group(3).endsWith("boxes") || pt.group(3).endsWith("stashes");
            if ((plural || every) && det.matches("the|my|our|your|those|these") && adj.isEmpty()) return Ref.of(det.matches("those|these") ? Ref.Kind.POINT : Ref.Kind.KNOWN).withType(type);
            if (pt.group(3).matches("storages?|stash(?:es)?")) return adj.isEmpty() ? Ref.of(Ref.Kind.STORAGE) : new Ref(Ref.Kind.NAMED, adj, "chest");
            if (pt.group(3).equals("drop off")) return Ref.of(Ref.Kind.DROPOFF);
            if (det.matches("this|that|these|those")) return adj.isEmpty() || adj.matches("one|1") ? Ref.of(Ref.Kind.POINT).withType(type) : new Ref(Ref.Kind.NAMED, adj, type);
            if (!adj.isEmpty() && !adj.matches("(?:one|1|chest|box|left|right)")) return new Ref(Ref.Kind.NAMED, adj, type);
            if (det.matches("my|our|your") && !type.matches("chest|barrel|box|")) return Ref.of(Ref.Kind.DEF).withType(type);
            if (det.matches("my|our")) return Ref.of(Ref.Kind.STORAGE).withType(type);
            if (det.matches("a|an")) return Ref.of(Ref.Kind.NEAREST).withType(type);
            return Ref.of(Ref.Kind.DEF).withType(type);
        }
        // "the iron one", "the wood one"
        Matcher one = Pattern.compile("^(?:the|my|our) ([a-z]+(?: [a-z]+)?) one$").matcher(p);
        if (one.matches() && !one.group(1).matches("(?:other|same|first|second|last|next|big|small|left|right)")) return new Ref(Ref.Kind.NAMED, one.group(1), "");
        if (p.matches("^(?:the |that |this )?other one$|^(?:the |that )?second one$")) return Ref.of(Ref.Kind.POINT2);
        // a saved place: "the mine", "the farm", "the quarry"
        Matcher wp = Pattern.compile("^(?:the|my|our) (mine|quarry|farm|field|forest|woods|cave|tunnel|pit|camp|village|town|outpost|tower|dock|harbour|harbor|portal|nether portal|base camp|workshop|forge|smeltery|lumber ?yard|wood ?yard|tree farm|iron farm|mob farm|[a-z]+ farm)$").matcher(p);
        if (wp.matches()) return new Ref(Ref.Kind.WAYPOINT, wp.group(1), "");
        return Ref.NONE;
    }

    /** A haul's end: a container, home, a saved place, or here. */
    static Ref place(String s) {
        Ref r = ref(s);
        if (r.kind() != Ref.Kind.NONE) return r;
        String p = s.trim().replaceAll("^(?:the|my|our) ", "");
        if (p.matches("here|right here|where i am|me")) return Ref.of(Ref.Kind.ME);
        if (p.matches("there|over there")) return Ref.of(Ref.Kind.POINT);
        if (p.matches("[a-z]+(?: [a-z]+)?") && !p.matches("(?:it|that|this|them|stop|everything)")) return new Ref(Ref.Kind.WAYPOINT, p, "");
        return Ref.NONE;
    }

    /** The kind of container a phrase names ("chest" when it names none). */
    static String type(String p) {
        if (p.matches(".*\\bblast furnace.*")) return "blast furnace";
        if (p.matches(".*\\bfurnace.*")) return "furnace";
        if (p.matches(".*\\bsmoker.*")) return "smoker";
        if (p.matches(".*\\bbrewing stand.*")) return "brewing stand";
        if (p.matches(".*\\bcomposter.*")) return "composter";
        if (p.matches(".*\\bhopper.*")) return "hopper";
        if (p.matches(".*\\bdispenser.*")) return "dispenser";
        if (p.matches(".*\\bdropper.*")) return "dropper";
        if (p.matches(".*\\bshulker.*")) return "shulker";
        if (p.matches(".*\\bender ?chest.*")) return "ender chest";
        if (p.matches(".*\\bbarrel.*")) return "barrel";
        return "chest";
    }

    // ------------------------------------------------------------------ things

    private static final Pattern P_ALL_ITEMS = Pattern.compile("^(?:all|everything|every thing|it all|all of it|the lot|the whole lot|all items|all the items|all your items|all of your items|items|your items|all your items|everything you have|everything you ve got|everything you got|your pack|your inventory|whatever you have|whatever you ve got|all your|all of them|the rest|the rest of it|all the rest|everything else|your load|the load|your haul|the haul|all that|all this|all of that|all of this|your)$");

    /**
     * The thing named, as a plain word or phrase ("3 stacks of oak" -> "oak", "the ingots" -> "ingots", "all my cobble"
     * -> "cobblestone", "everything" -> "*", "them" -> "it"). The verb helps: "chop 3 stacks" means logs.
     */
    static String item(String s, String verb) {
        if (s == null) return "";
        String t = " " + s.toLowerCase(Locale.ROOT).trim() + " ";
        if (P_ALL_ITEMS.matcher(t.trim()).matches()) return "*";
        t = t.replaceAll(" (?:of|the|some|my|your|our|all of|all the|all|any|more|a|an|those|these|that|this|me|us|please|for me|now|up|back|em|ur|its|mine|of it|of them|worth|lot of|lots of|loads of|bunch of|pile of|full|whole|whatever|every|single|last|bit of|few|several|spare|extra|enough|remaining|leftover|left over|rest of) ", " ");
        t = t.replaceAll(" (?:of|the|some|my|your|our|all|any|more|a|an|those|these|that|this|me|us|please|for me|now|up|back|em|ur|its|mine|of it) ", " ");
        t = t.replaceAll("\\b(?:you can find|you can|you find|you have|you ve got|you got|you can carry|you can get|nearby|around here|from around here|round here|in the area|until you have \\d+|until it s full|till it s full|until full|for me|if you can|asap|right now|you|i have|i ve got)\\b", " ");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return verb != null && verb.matches("chop|cut|fell") ? "logs" : "";
        if (P_ALL_ITEMS.matcher(t).matches() || t.equals("*")) return "*";
        if (t.matches("^(?:it|them|they|that|this|those|these|em|same|the same|that stuff|this stuff|it in|them in)$")) return "it";
        if (t.matches("^(?:everything|all) (?:else|of it|from .*)$")) return "*";
        t = t.replaceAll("\\bitems?\\b$", "").trim();
        if (t.isEmpty()) return "*";
        t = t.replaceAll("\\bcobblestone\\b", "cobblestone").replaceAll("^(?:cobble|cobbles)$", "cobblestone");
        if (t.matches("^(?:oak|birch|spruce|jungle|acacia|dark oak|mangrove|cherry)(?: wood| logs?| trees?)?$")) return t.replaceAll(" (?:wood|logs?|trees?)$", "") + " log";
        if (t.matches("^(?:logs?|wood|trees?|timber|lumber)$")) return "logs";
        if (t.matches("^(?:raw )?(?:iron|gold|copper) ores?$|^raw (?:iron|gold|copper)$")) return "raw " + t.replaceAll("^raw ", "").replaceAll(" ores?$", "");
        if (t.matches("^(?:ores?|raw ores?|my ores?|the ores?|raw stuff|ore blocks?)$")) return "ore";
        if (t.matches("^(?:cooked|cooked food|cooked meat|cooked stuff|cooked items|prepared food|meals?|dinner|lunch|grub|scran|tucker)$")) return "cooked food";
        if (t.matches("^(?:food|snacks?|eats|nosh|provisions|rations)$")) return "food";
        if (t.matches("^(?:fuel|burnables?|coal or charcoal|something to burn)$")) return "fuel";
        if (t.matches("^(?:seeds?|wheat seeds?)$")) return "seeds";
        if (t.matches("^(?:iron|gold|copper) ingots?$")) return t.replaceAll(" ingots?$", " ingot");
        if (t.matches("^(?:ingots?|bars?|metal)$")) return "ingots";
        if (t.matches("^(?:building blocks?|blocks|building stuff|building materials?|materials?)$")) return "blocks";
        if (t.matches("^(?:gems?|valuables|precious stuff|treasure|shinies|shiny stuff)$")) return "valuables";
        if (t.matches("^(?:tools?|my tools?)$")) return "tools";
        if (t.matches("^(?:torch|torches|tourches|torchs)$")) return "torches";
        if (t.matches("^(?:bread|loaves|loaf|breads)$")) return "bread";
        if (t.matches("^(?:stone|stones|rock|rocks|cobblestone)$")) return t.equals("cobblestone") ? "cobblestone" : "stone";
        // a plural to its singular for the registry ("diamonds" -> "diamond"), but not words that end in s
        if (t.matches(".*[^s]s$") && !t.matches(".*(?:glass|grass|moss|logs|planks|sticks|seeds|carrots|potatoes|torches|arrows|beetroots|leaves|boots|leggings|shears|crops|ores|bricks|stairs|planks|bones|eggs|feathers|apples)$")) t = t.substring(0, t.length() - 1);
        if (t.length() > 32) return "";
        return t;
    }

    /** Words "where are my X" may mean items by (anything that isn't a place or a person). */
    private static boolean knownItemWord(String it, String said) {
        if (it.matches("(?:you|it|bed|house|home|base|dog|cat|horse|wolf|friend|wife|husband|keys|glasses|phone|mum|dad|mom|death|village|town|spawn|portal|mine|farm|market|shop|castle|nether|end|stronghold|cave|river|sea|ocean|desert|forest|mountain|everyone|everybody|north|south|east|west|player|well|church|chapel|tavern|inn|library|smithy|tower|wall|gate|bridge|road|path)")) return false;
        if (PaUnderstand.RESOURCES.containsKey(it) || PaUnderstand.RESOURCES.containsValue(it)) return true;
        for (String[] c : PaUnderstand.CRAFTABLES) if (c[0].equals(it) || c[1].equals(it)) return true;
        if (it.matches("(?:.* )?(?:ingot|ingots|ore|log|planks|bread|torches|arrows|food|tools|valuables|cooked food|fuel|seeds|nugget|block|blocks|dye|wool|bone|bones|string|feather|leather|egg|apple|steak|beef|porkchop|potato|carrot|book|paper|map|compass|clock|pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots|bow|shield|emerald|quartz|netherite|scrap|debris|glass|sand|gravel|flint|slime|pearl|blaze rod|rod|potion|bottle|bucket)s?")) return true;
        return said.matches("^(?:my|our|all my|all our) .*") || it.endsWith("s");
    }

    /** "stone" -> STONE kind etc.: the pure part a test can check (null when not a gatherer's kind). */
    static String gatherWord(String item) {
        String t = item.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(?:log|logs|wood|oak|birch|spruce|jungle|acacia|mangrove|cherry)\\b.*")) return "logs";
        if (t.matches("^(?:stone|cobblestone|cobble|rock)$")) return "stone";
        if (t.matches("^(?:dirt|sand|gravel|coal|iron|raw iron|obsidian|sugar cane|cane)$")) return t.replace("raw ", "").replace("cane", "sugar cane").replace("sugar sugar", "sugar");
        return null;
    }
}
