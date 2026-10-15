package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.ThemeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * [styles] The towns' looks, as data (village/ThemeData): every theme the mod and the world's datapacks carry
 * (data/&lt;namespace&gt;/themes/&lt;id&gt;.json), the theme each town builds in, and the theme's words turned into the
 * game's blocks and items.
 *
 * <p>A town's theme is kept in the ledger ("theme"); without one it is the config's (villageTheme, "medieval"). The
 * builders (BuildGoal through Palettes), the ages' make-overs (Ages), the building styles' dressing (Architecture), the
 * street furniture, the stores' choice of a stand-in (Materials) and the renovation (Renovation) all ask here, and
 * none of them names a block of the look itself. A theme may draw its own version of any design
 * (blueprints/&lt;theme&gt;/&lt;design&gt;.txt), which its towns build in place of the usual one (design()).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Themes {

    private Themes() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The themes the datapacks gave, by id, as written (the mod's own among them once the server has loaded its data). */
    private static final Map<String, ThemeData> LOADED = new ConcurrentHashMap<>();
    /** Each theme with its parent's put under it, made once. */
    private static final Map<String, ThemeData> RESOLVED = new ConcurrentHashMap<>();
    /** Themes asked for that are nowhere: asked once. */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    /** The ledger's key for a town's theme. */
    static final String NOTE = "theme";

    // ------------------------------------------------------------------ loading

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Map<String, String>>() {
            @Override
            protected Map<String, String> prepare(ResourceManager rm, ProfilerFiller profiler) {
                Map<String, String> out = new TreeMap<>();
                for (Map.Entry<ResourceLocation, Resource> e : rm.listResources("themes", rl -> rl.getPath().endsWith(".json")).entrySet()) {
                    String path = e.getKey().getPath();
                    String id = path.substring("themes/".length(), path.length() - ".json".length());
                    if (!e.getKey().getNamespace().equals(McAssistantMod.MODID)) id = e.getKey().getNamespace() + "." + id;
                    try (InputStream in = e.getValue().open()) {
                        out.put(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (Exception x) {
                        LOG.error("[MCA-THEME] could not read {}: {}", e.getKey(), x.toString());
                    }
                }
                return out;
            }

            @Override
            protected void apply(Map<String, String> got, ResourceManager rm, ProfilerFiller profiler) {
                Map<String, ThemeData> read = new HashMap<>();
                for (Map.Entry<String, String> e : got.entrySet()) {
                    try {
                        read.put(e.getKey(), ThemeData.read(e.getKey(), e.getValue()));
                    } catch (Exception x) {
                        LOG.error("[MCA-THEME] theme {} is not good JSON: {}", e.getKey(), x.getMessage());
                    }
                }
                LOADED.clear();
                LOADED.putAll(read);
                RESOLVED.clear();
                MISSING.clear();
                COMPILED.clear();
                Materials.forget();
                Palettes.forgetAll();
                LOG.info("[MCA-THEME] themes: {}", new java.util.TreeSet<>(LOADED.keySet()));
            }
        });
    }

    /** A theme as written: a datapack's, else the mod's own (read from the jar: before the data is loaded, the tests). */
    @Nullable
    private static ThemeData raw(String id) {
        ThemeData t = LOADED.get(id);
        if (t != null || MISSING.contains(id)) return t;
        if (!id.matches("[a-z0-9_.\\-]+")) return null;
        try (InputStream in = Themes.class.getResourceAsStream("/data/" + McAssistantMod.MODID + "/themes/" + id + ".json")) {
            if (in != null) {
                t = ThemeData.read(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                LOADED.putIfAbsent(id, t);
                return LOADED.get(id);
            }
        } catch (Exception x) {
            LOG.error("[MCA-THEME] could not read theme {}: {}", id, x.toString());
        }
        MISSING.add(id);
        return null;
    }

    /** A theme with its parent (and theirs) put under it; null if there is no such theme. */
    @Nullable
    public static ThemeData get(@Nullable String id) {
        if (id == null) return null;
        ThemeData got = RESOLVED.get(id);
        if (got != null) return got;
        ThemeData t = raw(id);
        if (t == null) return null;
        List<String> seen = new ArrayList<>();
        seen.add(id);
        ThemeData out = t;
        String p = t.parent();
        while (p != null && !seen.contains(p)) {
            seen.add(p);
            ThemeData up = raw(p);
            if (up == null) break;
            out = out.over(up);
            p = up.parent();
        }
        RESOLVED.put(id, out);
        return out;
    }

    /** The medieval theme: always there (it ships in the jar). */
    public static ThemeData medieval() {
        ThemeData t = get(ThemeData.DEFAULT);
        if (t == null) throw new IllegalStateException("the medieval theme is missing from the jar");
        return t;
    }

    /** The themes a town may be given (the config, the command): every selectable one known. */
    public static List<ThemeData> offered() {
        get(ThemeData.DEFAULT);
        List<ThemeData> out = new ArrayList<>();
        for (String id : new java.util.TreeSet<>(LOADED.keySet())) {
            ThemeData t = get(id);
            if (t != null && t.selectable()) out.add(t);
        }
        return out;
    }

    /** The theme a new town takes: the config's, if there is such a theme, else medieval. */
    public static String defaultId() {
        String c = AssistantConfig.villageTheme();
        return c != null && get(c) != null ? c : ThemeData.DEFAULT;
    }

    /** The id of a town's theme. */
    public static String idOf(@Nullable UUID village) {
        if (village == null) return defaultId();
        String s = Ledger.note(village, NOTE);
        return s != null && !s.isEmpty() && get(s) != null ? s : defaultId();
    }

    /** A town's theme (medieval for nobody's). */
    public static ThemeData of(@Nullable UUID village) {
        ThemeData t = get(idOf(village));
        return t != null ? t : medieval();
    }

    /**
     * Give a town a theme (the command; the tests may give one that is not offered). Its builders reach for the new
     * look at once, and its older buildings are renovated to it as the builders come round (Renovation). False if there
     * is no such theme.
     */
    public static boolean set(UUID village, String id) {
        if (get(id) == null) return false;
        Ledger.note(village, NOTE, id);
        Palettes.forget(village);
        Renovation.resurvey(village);
        return true;
    }

    /** Tests: everything worked out forgotten (the datapacks' themes are kept). */
    public static void resetForTests() {
        RESOLVED.clear();
        MISSING.clear();
        COMPILED.clear();
        Materials.forget();
    }

    // ------------------------------------------------------------------ the theme's own designs

    /**
     * The design a town of this theme builds for a building: its theme's own drawing of it
     * (blueprints/&lt;theme&gt;/&lt;design&gt;.txt, or its parent's), named "&lt;theme&gt;/&lt;design&gt;" for Blueprints; else
     * the usual one. A storey put on ("_tall") is the theme's design raised.
     */
    public static String design(@Nullable UUID village, String structure) {
        if (structure == null || structure.indexOf('/') >= 0) return structure;
        String base = structure.endsWith(Blueprints.TALL) ? structure.substring(0, structure.length() - Blueprints.TALL.length()) : structure;
        String id = idOf(village);
        List<String> seen = new ArrayList<>();
        while (id != null && !seen.contains(id)) {
            seen.add(id);
            if (Blueprints.drawn(id + "/" + base)) return id + "/" + structure;
            ThemeData t = raw(id);
            id = t == null ? null : t.parent();
        }
        return structure;
    }

    /** A design's own name, without its theme: "house" for "alien/house_tall"'s building ("house_tall"). */
    public static String bare(String drawing) {
        int slash = drawing.lastIndexOf('/');
        return slash < 0 ? drawing : drawing.substring(slash + 1);
    }

    // ------------------------------------------------------------------ words into blocks

    /** The block an id names; air if none. */
    public static Block block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(ThemeData.id(id));
        return rl == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(rl);
    }

    /** The item an id names; air if none. */
    public static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(ThemeData.id(id));
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private static final Map<String, Predicate<BlockState>> STATE_MATCH = new ConcurrentHashMap<>();
    private static final Map<String, Predicate<ItemStack>> ITEM_MATCH = new ConcurrentHashMap<>();

    /** Does the block standing match this word: an id, "#a_tag", or an id with * in it ("*quartz*")? */
    public static Predicate<BlockState> blocks(String word) {
        Predicate<BlockState> got = STATE_MATCH.get(word);
        if (got != null) return got;
        got = blocksOf(ThemeData.id(word));
        STATE_MATCH.put(word, got);
        return got;
    }

    private static Predicate<BlockState> blocksOf(String id) {
        return STATE_MATCH.computeIfAbsent(id, w -> {
            if (w.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(w.substring(1)));
                return s -> s.is(tag);
            }
            if (w.indexOf('*') >= 0) {
                Pattern p = glob(w);
                return s -> p.matcher(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).matches();
            }
            Block b = block(w);
            return b == Blocks.AIR ? s -> false : s -> s.is(b);
        });
    }

    /** Does the item match this word: an id, "#a_tag", or an id with * in it? */
    public static Predicate<ItemStack> items(String word) {
        Predicate<ItemStack> got = ITEM_MATCH.get(word);
        if (got != null) return got;
        got = itemsOf(ThemeData.id(word));
        ITEM_MATCH.put(word, got);
        return got;
    }

    private static Predicate<ItemStack> itemsOf(String id) {
        return ITEM_MATCH.computeIfAbsent(id, w -> {
            if (w.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(w.substring(1)));
                return s -> s.is(tag);
            }
            if (w.indexOf('*') >= 0) {
                Pattern p = glob(w);
                return s -> !s.isEmpty() && p.matcher(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).matches();
            }
            Item it = item(w);
            return it == Items.AIR ? s -> false : s -> s.is(it);
        });
    }

    private static Pattern glob(String w) {
        StringBuilder re = new StringBuilder();
        for (char c : w.toCharArray()) re.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        return Pattern.compile(re.toString());
    }

    // ------------------------------------------------------------------ the rules

    /** What a rule is asked about: the age, the building, the block standing there, and the cell's place in it. */
    public record Ctx(Villages.Age age, String structure, boolean home, BlockState now, BlockPos pos, @Nullable Block landStone,
                      boolean band, boolean side, @Nullable ThemeData.Look look, @Nullable String trim) {}

    /** A rule's answer: the block wanted (null: leave it as it is) and the words for the work. */
    public record Result(@Nullable BlockState want, String what) {}

    /** The first of these rules that holds, as an answer; null if none holds. */
    @Nullable
    static Result first(ThemeData t, List<Map<String, Object>> rules, Ctx c) {
        for (Map<String, Object> r : rules) {
            if (!holds(t, r, c)) continue;
            Object to = r.get("to");
            BlockState want = to instanceof String s ? resolve(s, c) : null;
            Object what = r.get("what");
            return new Result(want, what instanceof String w ? w : "");
        }
        return null;
    }

    private static boolean holds(ThemeData t, Map<String, Object> r, Ctx c) {
        for (Map.Entry<String, Object> e : r.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "age_min" -> { if (c.age().ordinal() < age(v)) return false; }
                case "age_max" -> { if (c.age().ordinal() > age(v)) return false; }
                case "age" -> { if (c.age().ordinal() != age(v)) return false; }
                case "structures" -> { if (!names(t, v).contains(c.structure())) return false; }
                case "not_structures" -> { if (names(t, v).contains(c.structure())) return false; }
                case "home" -> { if (c.home() != Boolean.TRUE.equals(v)) return false; }
                case "band" -> { if (c.band() != Boolean.TRUE.equals(v)) return false; }
                case "side" -> { if (c.side() != Boolean.TRUE.equals(v)) return false; }
                case "chance" -> {
                    int n = v instanceof Number num ? Math.max(1, num.intValue()) : 1;
                    if (Math.floorMod(c.pos().hashCode(), n) != 0) return false;
                }
                case "now" -> { if (!anyMatch(v, c)) return false; }
                // [build-world] The town's land, and a pick among towns (ageLook with a town): never without one.
                case "lands" -> {
                    UUID town = TOWN.get();
                    if (town == null || !names(t, v).contains(Homeland.of(town).name())) return false;
                }
                case "town_mod" -> {
                    UUID town = TOWN.get();
                    if (town == null || !(v instanceof List<?> xs) || xs.isEmpty() || !(xs.get(0) instanceof Number n)) return false;
                    int m = Math.floorMod(Long.hashCode(town.getMostSignificantBits() ^ (town.getLeastSignificantBits() * (2L * n.intValue() + 1))), Math.max(1, n.intValue()));
                    boolean any = false;
                    for (int i = 1; i < xs.size(); i++) if (xs.get(i) instanceof Number k && k.intValue() == m) any = true;
                    if (!any) return false;
                }
                case "now_not" -> { if (anyMatch(v, c)) return false; }
                default -> { }
            }
        }
        return true;
    }

    private static int age(Object v) {
        try {
            return Villages.Age.valueOf(String.valueOf(v)).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }

    @SuppressWarnings("unchecked")
    static Set<String> cachedSet(ThemeData t, String name) {
        return (Set<String>) COMPILED.computeIfAbsent("set|" + t.id + "|" + name, k -> Set.copyOf(t.set(name)));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cachedRules(ThemeData t, String what, String kind, @Nullable String style) {
        return (List<Map<String, Object>>) COMPILED.computeIfAbsent(what + "|" + t.id + "|" + style + "|" + kind,
            k -> List.copyOf(what.equals("ages") ? t.ageRules(kind) : t.dressRules(style, kind)));
    }

    private static Set<String> names(ThemeData t, Object v) {
        if (v instanceof String s && s.startsWith("@")) return cachedSet(t, s.substring(1));
        Set<String> out = new java.util.HashSet<>();
        if (v instanceof List<?> xs) for (Object x : xs) out.add(String.valueOf(x));
        else if (v instanceof String s) out.add(s);
        return out;
    }

    private static boolean anyMatch(Object v, Ctx c) {
        if (!(v instanceof List<?> xs)) return false;
        for (Object x : xs) {
            if (!(x instanceof String w)) continue;
            if (blocks(w.indexOf('{') >= 0 ? ThemeData.fill(w, c.look(), c.trim()) : w).test(c.now())) return true;
        }
        return false;
    }

    /**
     * A rule's "to", as a block: the first of its "|"-separated choices that is a block. A choice is an id (its
     * default state), "like:id" (set as the block standing is set: a stair's facing and half, a pane's joins),
     * "axis_run:id" or "axis_y:id" (a log lying along the wall's run, or standing), or "$land_stone" (the land's own
     * walling, if the town has one and the stores can pay for it). The look's {walls}, {roof}, {floor}, {frame} and the
     * town's {trim} colour are put in first.
     */
    @Nullable
    static BlockState resolve(String to, Ctx c) {
        for (String raw : ThemeData.fill(to, c.look(), c.trim()).split("\\|")) {
            String choice = raw.trim();
            if (choice.equals("$land_stone")) {
                if (c.landStone() != null) return c.landStone().defaultBlockState();
                continue;
            }
            String mode = "";
            int colon = choice.indexOf(':');
            if (colon > 0) {
                String head = choice.substring(0, colon);
                if (head.equals("like") || head.equals("axis_run") || head.equals("axis_y")) {
                    mode = head;
                    choice = choice.substring(colon + 1);
                }
            }
            Block b = block(choice);
            if (b == Blocks.AIR) continue;
            BlockState s = b.defaultBlockState();
            switch (mode) {
                case "like" -> s = Ages.like(c.now(), b);
                case "axis_run", "axis_y" -> {
                    Direction.Axis axis = mode.equals("axis_y") ? Direction.Axis.Y : RUN.get();
                    if (s.hasProperty(RotatedPillarBlock.AXIS) && axis != null) s = s.setValue(RotatedPillarBlock.AXIS, axis);
                }
                default -> { }
            }
            return s;
        }
        return null;
    }

    /** The run of the wall a cell is in, while a dressing rule is asked about it (Architecture.plan). */
    static final ThreadLocal<Direction.Axis> RUN = ThreadLocal.withInitial(() -> Direction.Axis.Y);

    // ------------------------------------------------------------------ what the rules are asked

    /** The theme's rules compiled for one question, cached. */
    private static final Map<String, Object> COMPILED = new ConcurrentHashMap<>();

    /** The building a drawing is of: without its theme or its storey ("alien/house_tall" is a house). */
    static String structure(String drawing) {
        String s = bare(drawing);
        return s.endsWith(Blueprints.TALL) ? s.substring(0, s.length() - Blueprints.TALL.length()) : s;
    }

    /**
     * [build-world] As below, for a town: the rules that ask after the town's land ("lands") or pick among towns
     * ("town_mod": [n, k...], the same for every block of the town, so a town is all of a piece and the next one may be
     * of another) hold only when the town is known. A real town of the Iron Age went grey stone brick under black slate
     * from one end to the other, and so did every other town; now a lowland town may roof in red tile and build its
     * trades' houses of brick, a forest town keep its shingles and its timber, a desert town roof in sandstone.
     */
    @Nullable
    public static BlockState ageLook(@Nullable UUID town, ThemeData t, Villages.Age age, String drawing, Blueprints.Style style,
                                     BlockState now, BlockPos pos, @Nullable Block landStone) {
        UUID was = TOWN.get();
        TOWN.set(town);
        try {
            return ageLook(t, age, drawing, style, now, pos, landStone);
        } finally {
            TOWN.set(was);
        }
    }

    /** [build-world] The town an age's rule is being asked for (ageLook), or null. */
    private static final ThreadLocal<UUID> TOWN = new ThreadLocal<>();

    /**
     * What a block of this kind should be in this age, given what stands there now (Ages.look); null to leave it. The
     * theme's thatch, if it has any, is re-roofed first (Thatch).
     */
    @Nullable
    public static BlockState ageLook(ThemeData t, Villages.Age age, String drawing, Blueprints.Style style, BlockState now,
                                     BlockPos pos, @Nullable Block landStone) {
        String structure = structure(drawing);
        if (t.thatch()) {
            BlockState thatch = Thatch.reroof(age, style, now);
            if (thatch != null) return thatch;
        }
        Ctx c = new Ctx(age, structure, cachedSet(t, "homes").contains(structure), now, pos, landStone, false, false, null, null);
        Result r = first(t, cachedRules(t, "ages", style.name(), null), c);
        return r == null ? null : r.want();
    }

    /** The dressing of a cell of a house in a building style (Architecture.plan): the block wanted and the words. */
    @Nullable
    public static Result dress(ThemeData t, Architecture.Style st, String kind, Ctx c, Direction.Axis run) {
        RUN.set(run);
        try {
            return first(t, cachedRules(t, "dress", kind, st.name()), c);
        } finally {
            RUN.set(Direction.Axis.Y);
        }
    }

    /** The land's own walling in this theme (Homeland.walls), or null for the usual. */
    @Nullable
    public static Homeland.Stone landStone(ThemeData t, @Nullable Homeland.Land land) {
        if (land == null) return null;
        Map<String, Object> m = t.map("land_stone", land.name());
        if (!(m.get("block") instanceof String id)) return null;
        Block b = block(id);
        if (b == Blocks.AIR) return null;
        List<Predicate<ItemStack>> pays = new ArrayList<>();
        if (m.get("pay") instanceof List<?> xs) for (Object x : xs) if (x instanceof String w) pays.add(items(w));
        Predicate<ItemStack> pay = s -> {
            for (Predicate<ItemStack> p : pays) if (p.test(s)) return true;
            return false;
        };
        int each = m.get("each") instanceof Number n ? n.intValue() : 1;
        return new Homeland.Stone(b, pay, each);
    }

    /** A building style's section of the theme (its ornaments, lamp post, bench...). */
    static Map<String, Object> style(ThemeData t, @Nullable Architecture.Style st) {
        return st == null ? Map.of() : t.style(st.name());
    }

    /**
     * A named set of blocks of the theme's for a building put up at once or a storey put on (Ages.CIVIC, Grow.STONE,
     * Grow.BRICK): the theme's "palettes" entry, each part falling back on the one given.
     */
    public static Showcase.Palette palette(@Nullable UUID village, String name, Showcase.Palette fallback) {
        return palette(of(village), name, fallback);
    }

    public static Showcase.Palette palette(ThemeData t, String name, Showcase.Palette fallback) {
        Map<String, Object> m = t.map("palettes", name);
        if (m.isEmpty()) return fallback;
        return new Showcase.Palette(pb(m, "walls", fallback.walls()), pb(m, "frame", fallback.frame()),
            pb(m, "roof_stair", fallback.roofStair()), pb(m, "roof_slab", fallback.roofSlab()), pb(m, "roof_block", fallback.roofBlock()),
            pb(m, "floor", fallback.floor()), pb(m, "door", fallback.door()), pb(m, "fence", fallback.fence()),
            pb(m, "gate", fallback.gate()), pb(m, "bed", fallback.bed()), pb(m, "carpet", fallback.carpet()));
    }

    private static Block pb(Map<String, Object> m, String k, Block fallback) {
        if (!(m.get(k) instanceof String id)) return fallback;
        Block b = block(id);
        return b == Blocks.AIR ? fallback : b;
    }

    /** The street's lamp post and its light (Ages.lamps): the theme's, else a spruce post and a lantern. */
    static Block streetPost(@Nullable UUID village) {
        String id = of(village).str("street", "lamp_post");
        Block b = id == null ? Blocks.AIR : block(id);
        return b == Blocks.AIR ? Blocks.SPRUCE_FENCE : b;
    }

    static Block streetLight(@Nullable UUID village) {
        String id = of(village).str("street", "light");
        Block b = id == null ? Blocks.AIR : block(id);
        return b == Blocks.AIR ? Blocks.LANTERN : b;
    }

    /**
     * Every block the theme names as its own material anywhere (its parts' lists for every land, its ages' and styles'
     * rules, its land stones, its palettes, its street): never held against it as forbidden (Materials).
     */
    public static Set<Block> named(ThemeData t) {
        @SuppressWarnings("unchecked")
        Set<Block> got = (Set<Block>) COMPILED.get("named|" + t.id);
        if (got != null) return got;
        Set<Block> out = new java.util.HashSet<>();
        List<String> lands = new ArrayList<>(t.map("lands").keySet());
        for (String part : t.parts()) {
            for (String land : lands) {
                ThemeData.Look base = t.look(land);
                List<ThemeData.Look> looks = new ArrayList<>();
                looks.add(base);
                for (Architecture.Style st : Architecture.Style.values()) looks.add(t.styled(st.name(), base));
                for (ThemeData.Look l : looks) for (String id : t.rankedIds(part, l)) out.add(block(id));
            }
        }
        collect(t.map("ages"), out);
        collect(t.map("styles"), out);
        collect(t.map("land_stone"), out);
        collect(t.map("palettes"), out);
        collect(t.map("street"), out);
        out.remove(Blocks.AIR);
        COMPILED.put("named|" + t.id, out);
        return out;
    }

    /**
     * The blocks the theme makes a place of this kind of (a Blueprints.Style's name): its part's lists for every land
     * and building style, what its ages and its styles' dressing turn such a block into, and its land stones. What a
     * renovation to the theme leaves standing in such a place (Renovation); its palettes and street are not counted.
     */
    public static Set<Block> namedFor(ThemeData t, String part) {
        @SuppressWarnings("unchecked")
        Set<Block> got = (Set<Block>) COMPILED.get("namedFor|" + t.id + "|" + part);
        if (got != null) return got;
        Set<Block> out = new java.util.HashSet<>();
        for (String land : t.map("lands").keySet()) {
            ThemeData.Look base = t.look(land);
            for (String id : t.rankedIds(part, base)) out.add(block(id));
            for (Architecture.Style st : Architecture.Style.values()) for (String id : t.rankedIds(part, t.styled(st.name(), base))) out.add(block(id));
        }
        collect(t.list("ages", part), out);
        collect(t.map("styles"), out);
        collect(t.map("land_stone"), out);
        out.remove(Blocks.AIR);
        COMPILED.put("namedFor|" + t.id + "|" + part, out);
        return out;
    }

    private static void collect(Object o, Set<Block> out) {
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("now".equals(e.getKey()) || "now_not".equals(e.getKey()) || "pay".equals(e.getKey()) || "what".equals(e.getKey())) continue;
                collect(e.getValue(), out);
            }
        } else if (o instanceof List<?> xs) {
            for (Object x : xs) collect(x, out);
        } else if (o instanceof String s) {
            for (String choice : s.split("\\|")) {
                String c = choice.trim();
                int colon = c.indexOf(':');
                if (colon > 0 && (c.startsWith("like:") || c.startsWith("axis_run:") || c.startsWith("axis_y:"))) c = c.substring(colon + 1);
                if (c.isEmpty() || c.startsWith("$") || c.startsWith("@") || c.contains("{") || c.contains(" ")) continue;
                if (!c.matches("[a-z0-9_:./\\-]+")) continue;
                out.add(block(c));
            }
        }
    }
}
