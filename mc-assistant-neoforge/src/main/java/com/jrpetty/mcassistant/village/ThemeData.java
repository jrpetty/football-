package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [styles] A town's look, as data: one theme (data/mc_assistant/themes/&lt;id&gt;.json, a datapack may override it or add
 * another). It says, for every kind of block a design asks for (Blueprints.Style: the wall, the floor, the roof stair,
 * the post...), which blocks a builder reaches for first, given the town's land and its building style (the woods of a
 * taiga town, the Hill Fort's stone); what each age makes of the town's buildings (stone walls in the Stone Age, slate in
 * the Iron Age, copper on the great roofs in the Diamond Age); how each building style dresses its houses (bands,
 * shutters, battlements, banners); the street furniture; and which blocks are never building material at all, and what
 * stands in for a short one, age by age (the materials).
 *
 * <p>The game's code asks the theme and never names a block of the look itself, so a new theme (the alien one) is new
 * data and the same folk. Plain Java with nothing of the game in it: the unit tests read a theme as the game does.
 * THEMES.md says how to write one.
 *
 * <p>A theme may name a {@code parent}: everything it does not say is its parent's (the sections that are maps of
 * their own, the lands, the parts, the styles..., key by key).
 */
public final class ThemeData {

    /** The look every town has unless told otherwise. */
    public static final String DEFAULT = "medieval";

    /** The sections merged key by key with a parent's, rather than replaced whole. */
    static final Set<String> MERGED = Set.of("lands", "land_stone", "parts", "ages", "styles", "sets", "palettes", "street",
        "materials", "fallback");

    public final String id;
    private final Map<String, Object> root;

    public ThemeData(String id, Map<String, Object> root) {
        this.id = id;
        this.root = root;
    }

    /** A theme read from its JSON text. */
    @SuppressWarnings("unchecked")
    public static ThemeData read(String id, String json) {
        Object o = ThemeJson.parse(json);
        if (!(o instanceof Map)) throw new IllegalArgumentException("theme " + id + ": not a JSON object");
        return new ThemeData(id, (Map<String, Object>) o);
    }

    /** The parent this theme names, or null. */
    public String parent() {
        Object p = root.get("parent");
        return p instanceof String s && !s.isBlank() ? s : null;
    }

    /** This theme over its parent: what it does not say, the parent's. */
    public ThemeData over(ThemeData parent) {
        return new ThemeData(id, merge(parent.root, root, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> under, Map<String, Object> over, int depth) {
        Map<String, Object> out = new LinkedHashMap<>(under);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object was = out.get(e.getKey());
            if (depth == 0 && MERGED.contains(e.getKey()) && was instanceof Map && e.getValue() instanceof Map) {
                out.put(e.getKey(), merge((Map<String, Object>) was, (Map<String, Object>) e.getValue(), depth + 1));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        out.remove("parent");
        if (depth == 0) {
            // Who it is and whether it is offered are the theme's own, never its parent's.
            out.put("selectable", over.getOrDefault("selectable", Boolean.FALSE));
            if (over.containsKey("name")) out.put("name", over.get("name"));
        }
        return out;
    }

    // ------------------------------------------------------------------ plain reading

    public String name() {
        Object n = root.get("name");
        return n instanceof String s ? s : id;
    }

    /** Offered to a town by the command and the config (a test theme is not). */
    public boolean selectable() {
        return Boolean.TRUE.equals(root.get("selectable"));
    }

    /** A line describing the theme, for the command's list. */
    public String about() {
        Object a = root.get("about");
        return a instanceof String s ? s : "";
    }

    /** Thatch first on a Wood Age roof, while there is wheat to spare (the work items' thatch: Thatch). */
    public boolean thatch() {
        return Boolean.TRUE.equals(root.get("thatch"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String... path) {
        Object o = root;
        for (String k : path) {
            if (!(o instanceof Map)) return Map.of();
            o = ((Map<String, Object>) o).get(k);
        }
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String... path) {
        if (path.length == 0) return List.of();
        String[] head = java.util.Arrays.copyOf(path, path.length - 1);
        Object o = (head.length == 0 ? root : map(head)).get(path[path.length - 1]);
        return o instanceof List ? (List<Object>) o : List.of();
    }

    public List<String> strings(String... path) {
        List<String> out = new ArrayList<>();
        for (Object o : list(path)) if (o instanceof String s) out.add(s);
        return out;
    }

    /** A string at this path, or null. */
    public String str(String... path) {
        if (path.length == 0) return null;
        String[] head = java.util.Arrays.copyOf(path, path.length - 1);
        Object o = (head.length == 0 ? root : map(head)).get(path[path.length - 1]);
        return o instanceof String s ? s : null;
    }

    // ------------------------------------------------------------------ ids

    /** A block or item id, namespaced: "cobblestone" is "minecraft:cobblestone", "#planks" the tag "#minecraft:planks". */
    public static String id(String raw) {
        String s = raw.trim();
        boolean tag = s.startsWith("#");
        if (tag) s = s.substring(1);
        if (s.indexOf(':') < 0) s = "minecraft:" + s;
        return tag ? "#" + s : s;
    }

    // ------------------------------------------------------------------ the woods and the lands

    /** The woods, best known first. */
    public List<String> woods() {
        return strings("woods");
    }

    /** The darker woods, put early on a roof's or a frame's list. */
    public List<String> darkWoods() {
        return strings("dark_woods");
    }

    /** A town's choice: the wood of its walls, its roofs, its floors, its frames; its footing and dressed stones. */
    public record Look(String walls, String roof, String floor, String frame, List<String> footing, List<String> dressed) {

        /** One of its fields by name (walls, roof, floor, frame). */
        public String field(String f) {
            return switch (f) {
                case "walls" -> walls;
                case "roof" -> roof;
                case "floor" -> floor;
                case "frame" -> frame;
                default -> null;
            };
        }

        public List<String> listField(String f) {
            return switch (f) {
                case "footing" -> footing;
                case "dressed" -> dressed;
                default -> List.of();
            };
        }
    }

    /** The look of a town on this land (a Homeland.Land's name), the "default" land's where it says nothing. */
    public Look look(String land) {
        Map<String, Object> d = map("lands", "default");
        Map<String, Object> l = map("lands", land);
        return new Look(pick(l, d, "walls"), pick(l, d, "roof"), pick(l, d, "floor"), pick(l, d, "frame"),
            picks(l, d, "footing"), picks(l, d, "dressed"));
    }

    private static String pick(Map<String, Object> l, Map<String, Object> d, String k) {
        Object o = l.get(k);
        if (!(o instanceof String)) o = d.get(k);
        return o instanceof String s ? s : "oak";
    }

    private static List<String> picks(Map<String, Object> l, Map<String, Object> d, String k) {
        Object o = l.get(k);
        if (!(o instanceof List)) o = d.get(k);
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> xs) for (Object x : xs) if (x instanceof String s) out.add(s);
        return Collections.unmodifiableList(out);
    }

    /** A town's look in its building style's woods (an Architecture.Style's name; null for none yet). */
    public Look styled(String style, Look base) {
        if (style == null) return base;
        Map<String, Object> o = map("styles", style, "look");
        if (o.isEmpty()) return base;
        return new Look(o.get("walls") instanceof String s ? s : base.walls(), o.get("roof") instanceof String s ? s : base.roof(),
            o.get("floor") instanceof String s ? s : base.floor(), o.get("frame") instanceof String s ? s : base.frame(),
            base.footing(), base.dressed());
    }

    // ------------------------------------------------------------------ the parts

    /**
     * The ids a builder of a town with this look reaches for, best first, for a block of this kind (a Blueprints.Style's
     * name): each of the part's lines expanded. A line is an id ("minecraft:bamboo_planks", the look's fields put in for
     * {walls}, {roof}, {floor} and {frame}), or one of:
     * <ul>
     * <li>{@code woods(F):pattern}: the look's wood F first, then the theme's woods, each put in for {} in the pattern;</li>
     * <li>{@code darkwoods(F):pattern}: the same with the darker woods put early (a roof, a frame);</li>
     * <li>{@code list(F):pattern}: each of the look's list F (its footing, its dressed stones).</li>
     * </ul>
     * Ids repeated are kept once, at the first.
     */
    public List<String> rankedIds(String part, Look look) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : strings("parts", part)) out.addAll(expand(line, look));
        return new ArrayList<>(out);
    }

    /** The parts the theme has a list for. */
    public Set<String> parts() {
        return map("parts").keySet();
    }

    List<String> expand(String line, Look look) {
        List<String> out = new ArrayList<>();
        int open = line.indexOf('('), close = line.indexOf("):");
        if (open > 0 && close > open) {
            String fn = line.substring(0, open), field = line.substring(open + 1, close), pattern = line.substring(close + 2);
            List<String> each = new ArrayList<>();
            switch (fn) {
                case "woods", "darkwoods" -> {
                    LinkedHashSet<String> w = new LinkedHashSet<>();
                    String first = look.field(field);
                    if (first != null) w.add(first);
                    if (fn.equals("darkwoods")) w.addAll(darkWoods());
                    w.addAll(woods());
                    each.addAll(w);
                }
                case "list" -> each.addAll(look.listField(field));
                default -> { }
            }
            for (String e : each) out.add(id(pattern.replace("{}", e)));
            return out;
        }
        out.add(id(fill(line, look, null)));
        return out;
    }

    /** A line with the look's fields put in: {walls}, {roof}, {floor}, {frame}, and {trim} if given. */
    public static String fill(String line, Look look, String trim) {
        String s = line;
        if (look != null) {
            s = s.replace("{walls}", look.walls()).replace("{roof}", look.roof()).replace("{floor}", look.floor())
                .replace("{frame}", look.frame());
        }
        if (trim != null) s = s.replace("{trim}", trim);
        return s;
    }

    // ------------------------------------------------------------------ the ages, the styles, the sets

    /** The theme's rules for what an age makes of a block of this kind (a Blueprints.Style's name), first match wins. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ageRules(String part) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list("ages", part)) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    /** A building style's section (an Architecture.Style's name). */
    public Map<String, Object> style(String style) {
        return map("styles", style);
    }

    /** The dressing rules of a building style for a kind of cell (WALL, POST, ROOF_STAIR, ROOF_SLAB, MASONRY, WINDOW). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> dressRules(String style, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list("styles", style, "dress", kind)) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    /** A named set of the theme's (the great buildings...). */
    public Set<String> set(String name) {
        return new LinkedHashSet<>(strings("sets", name));
    }

    // ------------------------------------------------------------------ the materials

    /** Blocks never building material in this theme, at any age, unless one of its own lists names them (ids, #tags). */
    public List<String> forbidden() {
        return strings("materials", "forbidden");
    }

    /** Rough fill (earth, gravel, sand): not to be seen in a wall, a floor or a roof from {@link #roughUntil} on. */
    public List<String> rough() {
        return strings("materials", "rough");
    }

    /** The last age (a Villages.Age's name) a builder may still fall back on rough fill or any block it has. */
    public String roughUntil() {
        String s = str("materials", "rough_until");
        return s == null ? "STONE" : s;
    }

    /**
     * What stands in for the block a design wants when the stores are short of it, best first, in this age: the
     * materials' fallback for the part (a Blueprints.Style's name) or its group, for the age or the latest age before it
     * the list names. Empty: the theme says nothing (the builder's own order holds).
     */
    public List<String> fallback(String part, String age, List<String> ageOrder) {
        Map<String, Object> f = map("materials", "fallback");
        String group = null;
        for (Map.Entry<String, Object> e : map("materials", "groups").entrySet()) {
            if (e.getValue() instanceof List<?> xs && xs.contains(part)) { group = e.getKey(); break; }
        }
        Object byAge = f.get(part);
        if (!(byAge instanceof Map) && group != null) byAge = f.get(group);
        if (!(byAge instanceof Map<?, ?> m)) return List.of();
        int at = ageOrder.indexOf(age);
        for (int k = at; k >= 0; k--) {
            Object o = m.get(ageOrder.get(k));
            if (o instanceof List<?> xs) {
                List<String> out = new ArrayList<>();
                for (Object x : xs) if (x instanceof String s) out.add(id(s));
                return out;
            }
        }
        return List.of();
    }

    /** The parts in no group of the materials (the earth of a flower box, the glass): never held to them. */
    public boolean governed(String part) {
        for (Object v : map("materials", "groups").values()) if (v instanceof List<?> xs && xs.contains(part)) return true;
        return false;
    }

    @Override
    public String toString() {
        return "theme " + id;
    }
}
