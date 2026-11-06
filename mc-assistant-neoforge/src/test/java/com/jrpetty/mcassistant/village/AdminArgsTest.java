package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [admin] The admin toolkit's words: the selectors, the flags and the values checked against an action (AdminArgs, AdminSpec). */
class AdminArgsTest {

    @Test
    void quotedValuesKeepTheirSpaces() {
        assertEquals(List.of("inspect", "folk:Ada Brook", "--dry-run"), AdminArgs.tokens("inspect folk:\"Ada Brook\" --dry-run"));
        assertEquals(List.of("a", ""), AdminArgs.tokens("a ''"));
    }

    @Test
    void townSelectors() {
        assertEquals(AdminArgs.TownSel.Kind.NEAREST, AdminArgs.town("nearest").kind());
        assertEquals(AdminArgs.TownSel.Kind.NEAREST, AdminArgs.town("").kind());
        assertEquals(AdminArgs.TownSel.Kind.ALL, AdminArgs.town("all").kind());
        AdminArgs.TownSel named = AdminArgs.town("Oakford");
        assertEquals(AdminArgs.TownSel.Kind.NAMED, named.kind());
        assertEquals("Oakford", named.value());
    }

    @Test
    void folkSelectors() {
        assertEquals(AdminArgs.FolkSel.Kind.ALL, AdminArgs.folk("@all").kind());
        AdminArgs.FolkSel trade = AdminArgs.folk("@Smith");
        assertEquals(AdminArgs.FolkSel.Kind.TRADE, trade.kind());
        assertEquals("smith", trade.value(), "a trade selector is matched without case");
        assertEquals(AdminArgs.FolkSel.Kind.NAMED, AdminArgs.folk("1b3c9e2a").kind());
    }

    @Test
    void selectorsAndFlagsAreTakenOutOfTheValues() {
        AdminSpec.Action add = AdminSpec.byPath("people add");
        AdminArgs a = AdminArgs.parse("50 town:Ashby --dry-run --snapshot", add);
        assertNull(a.error());
        assertEquals(List.of("50"), a.positional());
        assertEquals(50, a.num(0, 0));
        assertEquals("Ashby", a.town().value());
        assertTrue(a.dryRun());
        assertTrue(a.flag(AdminSpec.SNAPSHOT));
        assertTrue(!a.folkGiven());
    }

    @Test
    void valuesAreCheckedAgainstTheAction() {
        assertNotNull(AdminArgs.parse("501", AdminSpec.byPath("people add")).error(), "over the hard cap of 500");
        assertNotNull(AdminArgs.parse("lots", AdminSpec.byPath("people add")).error(), "not a number");
        assertNotNull(AdminArgs.parse("bronze", AdminSpec.byPath("ages set")).error(), "no such age");
        assertNotNull(AdminArgs.parse("", AdminSpec.byPath("ages set")).error(), "the age is wanted");
        assertNotNull(AdminArgs.parse("iron instant fill extra", AdminSpec.byPath("ages set")).error(), "too many values");
        assertNull(AdminArgs.parse("iron instant fill", AdminSpec.byPath("ages set")).error());
        assertNull(AdminArgs.parse("-250", AdminSpec.byPath("economy coin")).error(), "coin may be taken out");
        assertNotNull(AdminArgs.parse("", AdminSpec.byPath("people add")).error(), "the count is wanted");
    }

    @Test
    void everyActionIsWellFormed() {
        Set<String> paths = new HashSet<>();
        for (AdminSpec.Action a : AdminSpec.all()) {
            assertTrue(paths.add(a.path()), "one action per path: " + a.path());
            assertTrue(a.path().split(" ").length <= 2, "a path is one or two words: " + a.path());
            assertTrue(a.example().startsWith(a.path()), "its example uses its own words: " + a.example());
            assertNull(AdminArgs.parse(a.example().substring(a.path().length()), a).error(), "its example parses: " + a.example());
            boolean optional = false;
            for (AdminSpec.Param p : a.params()) {
                assertTrue(!optional || p.optional(), "no wanted value after an optional one: " + a.path());
                optional |= p.optional();
            }
        }
        for (AdminSpec.Area area : AdminSpec.Area.values()) assertTrue(!AdminSpec.of(area).isEmpty(), "every area has actions: " + area);
    }

    @Test
    void searchFindsByWordsAndSummary() {
        assertTrue(AdminSpec.search("age").contains(AdminSpec.byPath("ages set")));
        assertTrue(AdminSpec.search("treasury coin").contains(AdminSpec.byPath("economy coin")));
        assertTrue(AdminSpec.search("zzz-nothing").isEmpty());
    }
}
