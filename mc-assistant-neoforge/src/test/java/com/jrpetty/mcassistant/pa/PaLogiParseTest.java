package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa36] The logistics reader over its phrasings (src/test/resources/pa/logistics*.txt) and the quantity reader on its own. */
class PaLogiParseTest {

    static int[] score(String file, List<String> misses) throws IOException {
        int n = 0, ok = 0;
        for (String line : Files.readAllLines(Path.of("src/test/resources/pa/" + file), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|\\|");
            n++;
            String got = PaLogiParse.brief(f[0].trim());
            if (got.equals(f[1].trim())) ok++;
            else misses.add(f[0].trim() + "  want " + f[1].trim() + "  got " + got);
        }
        System.out.printf("[pa36] %s: %d/%d%n", file, ok, n);
        for (String m : misses) System.out.println("[pa36]   miss: " + m);
        return new int[]{ok, n};
    }

    @Test
    void phrasingsAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        int[] s = score("logistics.txt", misses);
        assertTrue(s[1] >= 200, "200+ phrasings: " + s[1]);
        assertEquals(s[1], s[0], "every logistics phrasing read: " + misses);
    }

    @Test
    void heldOutPhrasingsAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        int[] s = score("logistics_heldout.txt", misses);
        assertTrue(s[0] >= s[1] * 0.95, "held-out phrasings 95%+: " + misses);
    }

    @Test
    void ordersSurviveTheirSpec() {
        for (String said : List.of("gather 100 stone and put it in this chest", "keep the food chest stocked with 32 bread",
                "haul from the mine to base until i say stop", "move everything from this chest to that chest", "call this chest the iron chest")) {
            List<PaLogiOrder> os = PaLogiParse.orders(said);
            assertNotNull(os, said);
            for (PaLogiOrder o : os) assertEquals(o, PaLogiOrder.parse(o.spec()), "spec round trip: " + said);
        }
    }

    @Test
    void quantities() {
        String[][] cases = {
            {"100 stone", "100"}, {"a stack of logs", "1st"}, {"two stacks", "2st"}, {"2 stacks", "2st"}, {"half a stack", "32"},
            {"half stack of bread", "0.5st"}, {"a stack and a half", "1.5st"}, {"2 and a half stacks", "2.5st"}, {"a few", "4"}, {"a couple", "2"},
            {"loads of stone", "128"}, {"lots", "128"}, {"a fair few", "128"}, {"all of it", "all"}, {"everything", "all"}, {"the lot", "all"},
            {"a shulker's worth", "27st"}, {"a shulker box full", "27st"}, {"a chest full of", "27st"}, {"a double chest worth", "54st"},
            {"enough for a house", "128"}, {"enough stone for a house", "128"}, {"as much as you can carry", "carry"}, {"as much stone as you can carry", "carry"},
            {"a full pack", "carry"}, {"until it's full", "full"}, {"till its full", "full"}, {"to the brim", "full"}, {"fill it up", "full"},
            {"3 staks", "3st"}, {"3 stax", "3st"}, {"for stacks", "4st"}, {"to stacks", "2st"}, {"a gross", "144"}, {"a score", "20"},
            {"half a dozen", "6"}, {"64x", "64"}, {"x32", "32"}, {"2k", "2000"}, {"some", "some"}, {"a handful", "4"}, {"", "-"},
            {"level 12", "-"}, {"5 minutes", "-"}, {"8x8 area", "-"}, {"several stacks", "3st"}, {"a quarter stack", "0.25st"},
            {"plenty", "128"}, {"heaps", "128"}, {"a bunch", "128"}, {"max", "carry"}, {"as many as possible", "carry"},
        };
        for (String[] c : cases) assertEquals(c[1], PaQty.read(PaWords.numbers(c[0])).brief(), "\"" + c[0] + "\"");
        assertEquals(16, PaQty.Qty.stacks(1).items(16, 0), "a stack of eggs is 16");
        assertEquals(32, PaQty.Qty.stacks(0.5).items(64, 0));
        assertEquals("oak", PaQty.strip("2 stacks of oak"));
        assertEquals("stone", PaQty.strip("100 stone"));
    }
}
