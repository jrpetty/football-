package com.jrpetty.mcassistant.client.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [guide31] The Guide answers what players really type: two hundred and more questions, typos, slang and all
 * (src/test/resources/guide_eval.txt, "question => article id"), each with the article it should answer with. The best
 * answer must be the right one for nine in ten, and the right one must be among the first three for 98 in a hundred.
 * The numbers and every miss are printed. Also: the reader's pieces (stemming, typos, intent) and the engine's three
 * ways of answering (sure, two close, nothing fits) behave.
 */
class GuideEvalTest {

    record Case(String question, String expect) {}

    static List<Case> cases() throws IOException {
        List<Case> out = new ArrayList<>();
        for (String line : Files.readAllLines(GuideCoverageTest.project().resolve("src/test/resources/guide_eval.txt"), StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int at = l.lastIndexOf("=>");
            if (at < 0) throw new AssertionError("bad eval line: " + line);
            out.add(new Case(l.substring(0, at).strip(), l.substring(at + 2).strip()));
        }
        return out;
    }

    @Test
    @DisplayName("The eval: 90% top-1, 98% top-3")
    void eval() throws IOException {
        GuideBook book = GuideCoverageTest.book();
        GuideSearch search = new GuideSearch(book);
        List<Case> cases = cases();
        assertTrue(cases.size() >= 200, "only " + cases.size() + " eval questions");
        int top1 = 0, top3 = 0;
        List<String> misses = new ArrayList<>();
        for (Case c : cases) {
            assertNotNull(book.get(c.expect()), "the eval expects an article that is not there: " + c.expect());
            List<GuideSearch.Hit> hits = search.rank(c.question());
            int rank = -1;
            for (int i = 0; i < Math.min(3, hits.size()); i++) if (hits.get(i).entry().id().equals(c.expect())) { rank = i; break; }
            if (rank == 0) top1++;
            if (rank >= 0) top3++;
            if (rank != 0) {
                StringBuilder sb = new StringBuilder("  " + (rank < 0 ? "MISS " : "top" + (rank + 1) + " ") + "\"" + c.question() + "\" want "
                    + c.expect() + ", got");
                for (int i = 0; i < Math.min(3, hits.size()); i++) sb.append(String.format(" %s(%.1f)", hits.get(i).entry().id(), hits.get(i).score()));
                misses.add(sb.toString());
            }
        }
        double p1 = top1 * 100.0 / cases.size(), p3 = top3 * 100.0 / cases.size();
        System.out.printf("[guide31] eval: %d questions, top-1 %d (%.1f%%), top-3 %d (%.1f%%)%n", cases.size(), top1, p1, top3, p3);
        misses.forEach(System.out::println);
        assertTrue(p1 >= 90.0, String.format("top-1 %.1f%% < 90%%", p1));
        assertTrue(p3 >= 98.0, String.format("top-3 %.1f%% < 98%%", p3));
    }

    @Test
    @DisplayName("Words are read as the book reads them")
    void reading() {
        assertEquals(GuideText.term("shopping"), GuideText.term("shop"));
        assertEquals(GuideText.term("villagers"), GuideText.term("folk"));
        assertEquals(GuideText.term("npc"), GuideText.term("townsfolk"));
        assertEquals(GuideText.term("cash"), GuideText.term("coins"));
        assertEquals(GuideText.term("store"), GuideText.term("shop"));
        assertEquals(GuideText.term("mayor"), GuideText.term("leader"));
        assertEquals(1, GuideText.distance("buisness", "business", 2));
        assertEquals(GuideText.Intent.WHY, GuideText.intent("why wont my folk work"));
        assertEquals(GuideText.Intent.HOW, GuideText.intent("how do i start a town"));
        assertEquals(GuideText.Intent.WHERE, GuideText.intent("where do i get coins"));
        assertEquals(GuideText.Intent.WHAT, GuideText.intent("what does the camera do"));
    }

    @Test
    @DisplayName("Sure, two close, or nothing fits: never a blank")
    void modes() {
        GuideSearch search = new GuideSearch(GuideCoverageTest.book());
        GuideSearch.Answer sure = search.answer("how do I make friends with the villagers", false);
        assertFalse(sure.mode() == GuideSearch.Mode.NONE);
        assertNotNull(sure.entry());
        GuideSearch.Answer none = search.answer("qwzx flibbertigibbet", false);
        assertEquals(GuideSearch.Mode.NONE, none.mode());
        assertEquals(3, none.also().size(), "nothing fits: the three nearest topics are still offered");
        GuideSearch.Answer typo = search.answer("how to start a buisness", false);
        assertTrue(typo.corrected().containsKey("buisness"), "the typo was not read: " + typo.corrected());
        for (GuideEntry e : search.book().entries()) {
            if (!e.op()) continue;
            GuideSearch.Answer a = search.answer(e.title(), false);
            assertTrue(a.entry() == null || !a.entry().op() || GuideText.terms(e.title()).contains("admin")
                || GuideText.terms(e.title()).contains("setting"), "an operator's article shown to a player: " + e.id());
        }
    }
}
