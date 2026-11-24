package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33f] Casual remarks read by their shape: praise, insults, laughter, and chat that isn't an order. */
class PaRemarksTest {

    private static void expect(String kind, String... said) {
        StringBuilder miss = new StringBuilder();
        for (String s : said) {
            PaQuips.Comeback got = PaQuips.comeback(s);
            if (got == null || !got.name().equals(kind)) miss.append('"').append(s).append("\" -> ").append(got).append("; ");
        }
        assertTrue(miss.isEmpty(), kind + ": " + miss);
    }

    @Test
    void praiseInAnyShape() {
        expect("PRAISE",
            "you're hilarious", "you are hilarious", "ur hilarious", "youre funny", "you're so funny", "that was hilarious", "that was funny",
            "you're a legend", "you absolute legend", "ur a legend mate", "what a legend", "you're class", "that was class", "class",
            "you're mint", "that's mint", "you're sound", "you're ace", "you're top", "that was the best", "you're a lad", "what a lad",
            "you're quality", "that was quality", "that's banging", "you're lush", "well funny", "proper good", "dead good", "you're well funny",
            "that was proper good", "that's dead good", "you're proper funny", "you're a top lad", "what a star", "you're a genius",
            "that was a banger", "you're brill", "you're a gem", "that was spot on", "you're on fire", "you're the goat", "that was elite",
            "not bad", "not bad at all", "that wasn't bad", "you're not useless", "you aren't rubbish", "you're hilarius", "ur hilaroius",
            "your hilarious", "u r funny", "you're amazing", "you're awesome mate", "that was absolutely brilliant", "you're a proper legend",
            "nice one", "legend", "absolute legend", "top banter", "you're a comedy gold", "that joke was funny", "your jokes are funny");
    }

    @Test
    void insultsInAnyShape() {
        expect("INSULT",
            "you're useless", "you are rubbish", "ur rubbish", "that was terrible", "that was awful", "you're not funny", "that wasn't funny",
            "you aren't funny", "you're so slow", "you're well slow", "that was pants", "you're naff", "what a muppet", "you numpty",
            "you're a wally", "you're thick", "you're dead slow", "that was cringe", "you're boring", "that joke was lame", "your jokes are rubbish",
            "you're a clown", "you're a joke", "you're the worst", "that was shocking", "you're a disgrace", "you're useles", "you're pathetic",
            "you're an absolute weapon", "what a melt", "you're a doughnut", "you're a spanner", "you're a prat", "you're not good",
            "you're not great", "that was dire", "you're hopeless mate", "you're clueless", "unfunny", "absolutely useless", "proper rubbish",
            "you're not very funny", "you're not that funny", "that was not funny", "you're garbage", "you're trash", "ur thick as mince");
    }

    @Test
    void laughterIsAReaction() {
        expect("LAUGH", "lol", "LOL", "haha", "hahaha", "hahahaha", "hehe", "lmao", "lmfao", "rofl", "😂", "🤣", "haha good one",
            "lol that's good", "i'm dying", "im dead", "dying", "crying", "you're killing me", "ha ha", "xd", "pmsl", "lmaooo", "loool");
    }

    @Test
    void ordersAndPlainWordsAreNotRemarks() {
        for (String s : List.of("follow me", "get me 16 logs", "build a house", "mine some iron", "come here", "make it look nice",
                "build a nice house", "get some good wood", "plant some nice flowers", "chop that big tree", "you go first", "your turn",
                "you good", "you ok", "fire", "diamond", "gold", "go to bed", "shoot that target", "it's broken", "stay here",
                "can you get me a diamond", "what are you doing", "where's your bed"))
            assertNull(PaRemarks.read(s), "not a remark: " + s);
    }

    @Test
    void chatIsNotAMenu() {
        for (String s : List.of("nice weather today", "i had a long day", "minecraft is great", "my dog says hi", "i'm bored", "what a day",
                "the sunset looks lovely", "i like your hat", "bit cold today"))
            assertTrue(PaRemarks.chat(s), "chat: " + s);
        for (String s : List.of("get me some flibber", "build a zorbon", "make me a snarg", "please fetch the wotsit", "can you mine the glorp",
                "where is the thing", "how many logs do you have"))
            assertFalse(PaRemarks.chat(s), "an order (misheard) is asked back about: " + s);
    }

    @Test
    void jokesAndLoveStillWin() {
        assertEquals(PaQuips.Comeback.JOKE, PaQuips.comeback("tell me a joke"));
        assertEquals(PaQuips.Comeback.JOKE, PaQuips.comeback("another joke"));
        assertEquals(PaQuips.Comeback.LOVE, PaQuips.comeback("you're the best"));
        assertEquals(PaQuips.Comeback.LOVE, PaQuips.comeback("love you"));
    }
}
