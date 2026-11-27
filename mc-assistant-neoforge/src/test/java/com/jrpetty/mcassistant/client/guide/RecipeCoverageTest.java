package com.jrpetty.mcassistant.client.guide;

import com.jrpetty.mcassistant.village.ThemeJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [recipes] A survival player can get every block and item the mod adds, with no commands (a server may ban them):
 * each has a crafting recipe of the mod's, or is placed by a thing that has one (the rope by its coil, a place setting
 * by its plate or cup), or is on the short list below of things issued in the game and never made (a made one would be
 * a forgery or a nonsense), each with the way to get it, named in the Guide. And every recipe a player may follow is in
 * the recipe book from the first moment in the world: an unlock advancement of the mod's grants it on the first tick.
 * The masters' recipes (mc_assistant:trained) open with an apprenticeship's lesson instead, and the tabard's
 * decoration is one of the game's hidden kinds (like dyeing), so neither wants one.
 *
 * <p>A new block with no recipe fails here by name: give it a recipe (and its unlock advancement under
 * data/mc_assistant/advancement/recipes), or put it on {@link #ISSUED} with how a player gets it, and say so in the Guide.
 */
class RecipeCoverageTest {

    private static final String DATA = "src/main/resources/data/mc_assistant/";

    /** Issued in the game, never crafted: what it is, and the way a player gets one (the Guide's items_not_crafted says it too). */
    static final Map<String, String> ISSUED = new LinkedHashMap<>();
    static {
        ISSUED.put("citizen_papers", "written at a Town Hall desk on joining a town; a replacement is 2 coins at the desk");
        ISSUED.put("deed", "the register's copy of a title, handed over with a purchase of land or a house; a made one is a forgery");
        ISSUED.put("village_coin", "struck at the town's mint: gold ingots at a market stall's counter, eight coins each, or earned");
        ISSUED.put("memory_core", "forms when a companion falls, holding all it was; a made one would hold nobody");
        ISSUED.put("working_model", "the scholars build one as they finish an invention; it goes to the stores and the museum");
    }

    /** Blocks with no item of their own, placed by one that has a recipe. */
    static final Map<String, List<String>> PLACED_BY = Map.of(
        "rope", List.of("rope_coil"),
        "place_setting", List.of("plate", "cup"));

    /** Recipe types a player does not find in the book from the start, and why. */
    static final Map<String, String> NOT_IN_THE_BOOK = Map.of(
        "mc_assistant:trained", "a master's recipe, granted at an apprenticeship's second lesson (PlayerTrades.complete)",
        "mc_assistant:tabard_decoration", "a hidden kind, like dyeing: any tabard and a banner pattern");

    /** Recipe kinds of the mod's own whose JSON names no result, and what each makes (QuiltRecipe: any six wool of three colours). */
    static final Map<String, String> CUSTOM_MAKES = Map.of("mc_assistant:patchwork_quilt", "patchwork_quilt");

    private static Object json(Path p) {
        try {
            return ThemeJson.parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> files(String dir) {
        try (Stream<Path> s = Files.walk(GuideCoverageTest.project().resolve(DATA + dir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String name(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".json".length());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Each recipe of the mod's, by name: its type and what it makes (the path under mc_assistant, or "" for a hidden kind). */
    private record Recipe(String type, String makes) {}

    private static Map<String, Recipe> recipes() {
        Map<String, Recipe> out = new TreeMap<>();
        for (Path p : files("recipe")) {
            Map<String, Object> r = map(json(p));
            String type = String.valueOf(r.get("type"));
            String id = String.valueOf(map(r.get("result")).getOrDefault("id", ""));
            if (CUSTOM_MAKES.containsKey(type)) id = "mc_assistant:" + CUSTOM_MAKES.get(type);
            out.put(name(p), new Recipe(type, id.startsWith("mc_assistant:") ? id.substring("mc_assistant:".length()) : ""));
        }
        return out;
    }

    private static Set<String> made(Map<String, Recipe> recipes) {
        Set<String> out = new TreeSet<>();
        for (Recipe r : recipes.values()) if (!r.makes().isEmpty()) out.add(r.makes());
        return out;
    }

    @Test
    @DisplayName("every block and item has a recipe, a placer with one, or is issued in the game (and the Guide says how)")
    void everythingCanBeGot() {
        Map<String, Recipe> recipes = recipes();
        Set<String> made = made(recipes);
        Set<String> things = GuideCoverageTest.items();
        assertTrue(things.size() > 100 && recipes.size() > 100, "the reading broke: " + things.size() + " things, " + recipes.size() + " recipes");
        List<String> wanting = new ArrayList<>();
        for (String t : things) {
            if (made.contains(t) || ISSUED.containsKey(t)) continue;
            List<String> by = PLACED_BY.get(t);
            if (by != null && by.stream().anyMatch(made::contains)) continue;
            wanting.add(t);
        }
        assertTrue(wanting.isEmpty(), "no way for a survival player to get these (give each a recipe, or put it on ISSUED with how): " + wanting);

        List<String> stale = new ArrayList<>();
        for (String t : ISSUED.keySet()) if (!things.contains(t)) stale.add(t);
        for (String t : PLACED_BY.keySet()) if (!things.contains(t)) stale.add(t);
        assertTrue(stale.isEmpty(), "on the lists, and not in the mod: " + stale);

        List<String> forgeable = new ArrayList<>();
        for (String t : ISSUED.keySet()) if (made.contains(t)) forgeable.add(t);
        assertTrue(forgeable.isEmpty(), "issued in the game, and yet a recipe makes it (a forgery): " + forgeable);

        GuideEntry e = GuideCoverageTest.book().get("items_not_crafted");
        assertNotNull(e, "the Guide's article on the things you can't craft");
        List<String> unsaid = new ArrayList<>();
        for (String t : ISSUED.keySet()) if (!e.items().contains("mc_assistant:" + t)) unsaid.add(t);
        assertTrue(unsaid.isEmpty(), "issued in the game, and the Guide's items_not_crafted does not say how to get it: " + unsaid);
    }

    @Test
    @DisplayName("every recipe a player may follow is in the recipe book from the start")
    void everyRecipeInTheBook() {
        Map<String, Recipe> recipes = recipes();
        Map<String, String> unlockedBy = new TreeMap<>();
        List<String> late = new ArrayList<>(), stale = new ArrayList<>();
        for (Path p : files("advancement/recipes")) {
            Map<String, Object> a = map(json(p));
            boolean atOnce = false;
            for (Object c : map(a.get("criteria")).values()) if ("minecraft:tick".equals(map(c).get("trigger"))) atOnce = true;
            Object rewards = map(a.get("rewards")).get("recipes");
            if (!(rewards instanceof List<?> list)) continue;
            for (Object r : list) {
                String id = String.valueOf(r);
                if (!id.startsWith("mc_assistant:") || !recipes.containsKey(id.substring("mc_assistant:".length()))) {
                    stale.add(name(p) + " -> " + id);
                    continue;
                }
                unlockedBy.put(id.substring("mc_assistant:".length()), name(p));
                if (!atOnce) late.add(name(p));
            }
        }
        assertFalse(unlockedBy.isEmpty(), "found no recipe advancements: the reading broke");
        List<String> hidden = new ArrayList<>();
        for (Map.Entry<String, Recipe> r : recipes.entrySet()) {
            if (NOT_IN_THE_BOOK.containsKey(r.getValue().type())) continue;
            if (!unlockedBy.containsKey(r.getKey())) hidden.add(r.getKey());
        }
        assertTrue(hidden.isEmpty(), "recipes not in the book (add an unlock advancement with a minecraft:tick criterion under "
            + "data/mc_assistant/advancement/recipes): " + hidden);
        assertTrue(late.isEmpty(), "recipe advancements that wait for something before unlocking (add the tick criterion): " + late);
        assertTrue(stale.isEmpty(), "recipe advancements for recipes that are not there: " + stale);
    }
}
