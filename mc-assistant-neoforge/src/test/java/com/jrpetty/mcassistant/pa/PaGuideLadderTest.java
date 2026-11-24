package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa35] The guide says what the ladders do, word for word as the rungs stand, and what its health does. */
class PaGuideLadderTest {

    private static String guide() throws Exception {
        try (var in = PaGuideLadderTest.class.getResourceAsStream("/assets/mc_assistant/guide/assistant.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void guideMatchesTheLadders() throws Exception {
        String want = PaGearLadder.guideSentence();
        assertTrue(guide().contains(want), "the guide should say: " + want);
    }

    @Test
    void guideMatchesTheHearts() throws Exception {
        assertTrue(guide().contains("from 10 (like a player) to 20 in two rows"), "the guide's hearts");
    }
}
