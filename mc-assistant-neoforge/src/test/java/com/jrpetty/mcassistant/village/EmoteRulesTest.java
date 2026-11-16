package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.entity.EmoteRules;
import com.jrpetty.mcassistant.entity.EmoteRules.Who;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [anim] The player's emotes and how a folk answers one, by what it thinks of the player (EmoteRules). */
class EmoteRulesTest {

    private static Who who(int affinity) {
        return new Who(affinity, false, false, false, false, false);
    }

    @Test
    void everyEmoteIsAnActWithATimeAndTheWheelHasNoDuplicates() {
        Set<Act> seen = new HashSet<>();
        for (Act a : EmoteRules.EMOTES) {
            assertTrue(seen.add(a), "once on the wheel: " + a);
            assertTrue(EmoteRules.isEmote(a));
            assertTrue(EmoteRules.ticks(a) >= 20 && EmoteRules.ticks(a) <= 400, "a sensible length for " + a);
        }
        assertEquals(14, EmoteRules.EMOTES.length, "wave, bow, cheer, clap, point, beckon, shrug, salute, handshake, dance, sit, kneel, pray, think");
        assertFalse(EmoteRules.isEmote(Act.HAMMER), "a player does not hammer by emote");
    }

    @Test
    void aWaveIsWavedBackByThoseWhoLikeThePlayer() {
        assertEquals(Act.WAVE, EmoteRules.answer(Act.WAVE, who(40)));
        assertEquals(Act.WAVE, EmoteRules.answer(Act.WAVE, who(10)));
        assertEquals(Act.NONE, EmoteRules.answer(Act.WAVE, who(0)), "a stranger does not wave back");
        assertEquals(Act.NONE, EmoteRules.answer(Act.WAVE, who(-60)));
        assertEquals(Act.WAVE, EmoteRules.answer(Act.WAVE, new Who(0, true, false, false, false, false)), "its hired folk wave back");
    }

    @Test
    void aBowIsReturnedToTheHonouredAndTheBeloved() {
        assertEquals(Act.BOW, EmoteRules.answer(Act.BOW, new Who(0, false, true, false, false, false)), "its leader, a knight of its town");
        assertEquals(Act.BOW, EmoteRules.answer(Act.BOW, who(70)));
        assertEquals(Act.WAVE, EmoteRules.answer(Act.BOW, who(25)), "a friend waves");
        assertEquals(Act.NONE, EmoteRules.answer(Act.BOW, who(0)));
    }

    @Test
    void gatheringsCheerAndDanceAlong() {
        Who feast = new Who(0, false, false, false, true, false);
        assertEquals(Act.CHEER, EmoteRules.answer(Act.CHEER, feast));
        assertEquals(Act.DANCE, EmoteRules.answer(Act.DANCE, feast));
        assertEquals(Act.CLAP, EmoteRules.answer(Act.CLAP, feast));
        assertEquals(Act.NONE, EmoteRules.answer(Act.DANCE, who(0)), "nobody dances alone with a stranger in the street");
    }

    @Test
    void aBeckonIsFollowedByItsOwnAndAPointByItsHired() {
        Who hired = new Who(0, true, false, false, false, false);
        assertTrue(EmoteRules.follows(Act.BECKON, hired));
        assertTrue(EmoteRules.follows(Act.BECKON, who(60)), "a close friend comes along");
        assertFalse(EmoteRules.follows(Act.BECKON, who(20)));
        assertTrue(EmoteRules.goesWherePointed(Act.POINT, hired));
        assertFalse(EmoteRules.goesWherePointed(Act.POINT, who(90)), "only its hired folk go where it points");
        assertFalse(EmoteRules.follows(Act.WAVE, hired));
    }

    @Test
    void aHandshakeIsTakenByTheOneOfferedItUnlessItCannotStandThePlayer() {
        assertEquals(Act.HANDSHAKE, EmoteRules.answer(Act.HANDSHAKE, new Who(0, false, false, false, false, true)));
        assertEquals(Act.SHRUG, EmoteRules.answer(Act.HANDSHAKE, new Who(-50, false, false, false, false, true)));
        assertEquals(Act.NONE, EmoteRules.answer(Act.HANDSHAKE, who(80)), "not offered to it: it carries on");
    }

    @Test
    void theWatchSalutesBack() {
        assertEquals(Act.SALUTE, EmoteRules.answer(Act.SALUTE, new Who(0, false, false, true, false, false)));
        assertEquals(Act.NONE, EmoteRules.answer(Act.SALUTE, new Who(-30, false, false, true, false, false)), "not a player it dislikes");
        assertEquals(Act.SALUTE, EmoteRules.answer(Act.SALUTE, new Who(-30, false, true, true, false, false)), "but its leader, always");
    }

    @Test
    void actsHaveStableNamesForTheCommand() {
        for (Act a : Act.values()) assertEquals(a, Act.named(a.word()));
        assertEquals(Act.STIR_POT, Act.named("stir-pot"));
        assertEquals(Act.NONE, Act.of(9999));
    }
}
