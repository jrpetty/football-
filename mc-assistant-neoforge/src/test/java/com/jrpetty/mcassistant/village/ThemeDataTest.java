package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [styles] The themes read as the game reads them (village/ThemeData, no Minecraft classes): the medieval theme is
 * whole (every land, every building style, every part the builders ask for, the materials), a part's list expands as
 * the builders' did, a theme over its parent keeps what it does not say, and bad JSON says where it is bad.
 */
class ThemeDataTest {

    private static final String THEMES = "src/main/resources/data/mc_assistant/themes";
    private static final String TEST_THEMES = "src/gametest/resources/data/mc_assistant/themes";

    private static Path project() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p : List.of(here, here.resolve("mc-assistant-neoforge"))) {
            if (Files.isDirectory(p.resolve(THEMES))) return p;
        }
        throw new AssertionError("no themes under " + here);
    }

    private static ThemeData read(String dir, String id) throws IOException {
        return ThemeData.read(id, Files.readString(project().resolve(dir).resolve(id + ".json"), StandardCharsets.UTF_8));
    }

    /** The lands (Homeland.Land) and building styles (Architecture.Style), by name. */
    private static final List<String> LANDS = List.of("COAST", "RIVER", "FOREST", "TAIGA", "SNOW", "MOUNTAIN", "DESERT", "SAVANNA",
        "JUNGLE", "SWAMP", "BADLANDS", "MEADOW", "PLAINS");
    private static final List<String> STYLES = List.of("TIMBERED", "HILL_FORT", "COASTAL", "DESERT_COURT", "FOREST_LODGE", "GRAND_CIVIC");
    /** The parts a builder of the medieval theme reaches for a list for (Palettes.ranked). */
    private static final List<String> PARTS = List.of("WALL", "FLOOR", "ROOF_BLOCK", "ROOF_STAIR", "ROOF_STAIR_TOP", "ROOF_SLAB",
        "ROOF_SLAB_TOP", "POST", "BEAM_ACROSS", "BEAM_ALONG", "FOUNDATION", "WALL_LOW", "MASONRY");

    @Test
    @DisplayName("the medieval theme is whole: every land, style and part, and its materials")
    void medievalIsWhole() throws IOException {
        ThemeData t = read(THEMES, ThemeData.DEFAULT);
        assertTrue(t.selectable(), "medieval is offered");
        for (String land : LANDS) {
            ThemeData.Look l = t.look(land);
            assertFalse(l.footing().isEmpty() || l.dressed().isEmpty(), "a footing and dressed stone on " + land);
        }
        for (String st : STYLES) assertFalse(t.style(st).isEmpty(), "the building style " + st);
        for (String part : PARTS) assertFalse(t.rankedIds(part, t.look("PLAINS")).isEmpty(), "a list for the part " + part);
        assertTrue(t.forbidden().contains("diamond_block") && t.forbidden().contains("netherrack"), "the precious and the Nether's forbidden");
        assertTrue(t.rough().contains("dirt"), "earth is rough fill");
        List<String> ages = List.of("WOOD", "STONE", "IRON", "DIAMOND", "NETHER");
        assertEquals("minecraft:stone_bricks", t.fallback("WALL", "DIAMOND", ages).get(0), "an Iron Age list serves the ages after it");
        assertTrue(t.fallback("SOIL", "IRON", ages).isEmpty() && !t.governed("SOIL"), "a flower box's earth is held to no list");
    }

    @Test
    @DisplayName("a part's list expands as the builders' did: the town's wood first, the darker woods early on a roof")
    void rankedExpands() throws IOException {
        ThemeData t = read(THEMES, ThemeData.DEFAULT);
        List<String> walls = t.rankedIds("WALL", t.look("TAIGA"));
        assertEquals(List.of("minecraft:spruce_planks", "minecraft:oak_planks", "minecraft:birch_planks", "minecraft:dark_oak_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
            "minecraft:bamboo_planks"), walls);
        List<String> roof = t.rankedIds("ROOF_STAIR", t.look("MEADOW"));
        assertEquals(List.of("minecraft:spruce_stairs", "minecraft:dark_oak_stairs", "minecraft:mangrove_stairs", "minecraft:oak_stairs"),
            roof.subList(0, 4));
        ThemeData.Look styled = t.styled("TIMBERED", t.look("PLAINS"));
        assertEquals("birch", styled.walls());
        assertEquals("dark_oak", styled.frame());
        assertEquals("spruce", styled.floor(), "a style leaves the floor to the land");
    }

    @Test
    @DisplayName("a theme over its parent: its own parts, its parent's lands, never its parent's offer")
    void parentFillsIn() throws IOException {
        ThemeData parent = read(THEMES, ThemeData.DEFAULT);
        ThemeData test = read(TEST_THEMES, "styles_test");
        assertEquals(ThemeData.DEFAULT, test.parent());
        ThemeData t = test.over(parent);
        assertEquals(List.of("minecraft:end_stone_bricks"), t.rankedIds("WALL", t.look("PLAINS")));
        assertEquals("spruce", t.look("TAIGA").walls(), "its parent's lands");
        assertFalse(t.selectable(), "the test theme is not offered, whatever its parent is");
        assertTrue(t.ageRules("WALL").isEmpty() && t.ageRules("ROOF_BLOCK").size() == 1, "its own ages, not its parent's");
        assertTrue(!t.forbidden().isEmpty(), "its parent's materials");
    }

    @Test
    @DisplayName("bad JSON is refused, saying where")
    void badJson() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ThemeData.read("bad", "{\n\"a\": [1, 2,\n}"));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        assertEquals(List.of("x"), ThemeData.read("ok", "// a comment\n{\"woods\": [\"x\",],}").woods(), "comments and trailing commas forgiven");
    }
}
