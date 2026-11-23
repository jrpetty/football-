package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33c-2b] Pings: the wheel for each target, the words each choice sends, the order window, the spam guard. */
class PaPingsTest {

    @Test
    void everyTargetHasItsOwnOptionsFirstAndTheCommonOnesLast() {
        for (PaPings.Target t : PaPings.Target.values()) {
            List<PaPings.Option> o = PaPings.options(t);
            assertTrue(o.size() >= 3 && o.size() <= 12, t + " has " + o.size());
            assertEquals("cancel", o.get(o.size() - 1).id(), t + ": cancel is last");
            assertTrue(o.stream().anyMatch(x -> x.id().equals("look")), t + ": look at this");
            assertTrue(o.stream().anyMatch(x -> x.id().equals("danger")), t + ": danger");
            Set<String> ids = new HashSet<>();
            for (PaPings.Option x : o) assertTrue(ids.add(x.id()), t + ": " + x.id() + " twice");
            assertNotNull(PaPings.quick(t));
        }
        assertEquals("chop", PaPings.quick(PaPings.Target.TREE).id());
        assertEquals("vein", PaPings.quick(PaPings.Target.ORE).id());
        assertEquals("attack", PaPings.quick(PaPings.Target.MOB).id());
        assertEquals("go", PaPings.quick(PaPings.Target.GROUND).id());
        assertEquals(PaPings.Target.GROUND, PaPings.of("nonsense"));
        assertEquals(PaPings.Target.ORE, PaPings.of("ore"));
    }

    @Test
    void everyChoiceIsAnOrderTheReaderUnderstands() {
        for (PaPings.Target t : PaPings.Target.values()) {
            for (PaPings.Option o : PaPings.options(t)) {
                if (o.says().isEmpty()) continue;
                PaUnderstand.Reading r = PaUnderstand.read(o.says(), "basil");
                assertTrue(r.clarify() == null && !r.steps().isEmpty(), t + "/" + o.id() + " \"" + o.says() + "\" -> " + r.brief());
                for (PaIntent i : r.steps()) assertFalse(i.kind() == PaIntent.Kind.UNKNOWN || i.kind() == PaIntent.Kind.QUESTION, o.says() + " -> " + r.brief());
            }
        }
        // what the pointed choices point at
        assertEquals("GATHER:logs@LOOKED", PaUnderstand.read(PaPings.option(PaPings.Target.TREE, "chop").says(), "basil").brief());
        assertEquals("MINE_ORE:vein@LOOKED", PaUnderstand.read(PaPings.option(PaPings.Target.ORE, "vein").says(), "basil").brief());
        assertEquals("GOTO@LOOKED", PaUnderstand.read(PaPings.option(PaPings.Target.GROUND, "go").says(), "basil").brief());
        assertEquals("BED_USE@LOOKED", PaUnderstand.read(PaPings.option(PaPings.Target.BED, "bed").says(), "basil").brief());
    }

    @Test
    void aBarePingBindsOnlyWithinItsWindow() {
        long t0 = 100_000;
        assertTrue(PaPings.binds(t0, t0 + 1000, "chop that"));
        assertTrue(PaPings.binds(t0, t0 + PaPings.WINDOW_MS, "go there"));
        assertFalse(PaPings.binds(t0, t0 + PaPings.WINDOW_MS + 1, "go there"), "too late: it was for the players");
        assertFalse(PaPings.binds(0, t0, "go there"), "no ping");
        assertFalse(PaPings.binds(t0, t0 + 10, " "), "nothing said");
        assertFalse(PaPings.binds(t0, t0 - 10, "go there"));
        assertTrue(PaPings.WINDOW_MS >= 4000 && PaPings.WINDOW_MS <= 5000);
        assertTrue(PaPings.MARK_MS >= 25_000 && PaPings.MARK_MS <= 35_000);
        assertEquals(PaPings.Sort.LOOK, PaPings.bare(), "a bare ping is neutral");
    }

    @Test
    void pointersAreCountedInOrder() {
        assertEquals(2, PaPings.pointers("chop that, and that, then come back"));
        assertEquals(1, PaPings.pointers("go there"));
        assertEquals(0, PaPings.pointers("come here"));
        assertEquals(0, PaPings.pointers(null));
    }

    @Test
    void spamIsHeldBack() {
        PaPings.Rate r = new PaPings.Rate();
        assertTrue(r.allow(1000));
        assertFalse(r.allow(1100), "a quarter second apart at least");
        int ok = 1;
        for (long t = 1300; t < 1300 + 60 * 300; t += 300) if (r.allow(t)) ok++;
        assertEquals(30, ok, "thirty a minute");
        assertTrue(r.allow(1000 + 61_000 + 60 * 300));
    }

    @Test
    void kindsAreColourAndShape() {
        Set<String> shapes = new HashSet<>();
        Set<Integer> colours = new HashSet<>();
        for (PaPings.Sort s : PaPings.Sort.values()) { shapes.add(s.shape); colours.add(s.colour); }
        assertEquals(PaPings.Sort.values().length, shapes.size(), "every kind its own shape, not colour alone");
        assertEquals(PaPings.Sort.values().length, colours.size());
    }

    @Test
    void voicePingsAreRead() {
        String[][] c = {{"go there", "GOTO@LOOKED"}, {"remember this spot as the mine", "WAYPOINT:mine@LOOKED"}, {"go to the mine", "GOTO:@mine"},
            {"ping the nearest iron", "FIND:iron"}, {"find me a tree", "FIND:logs"}, {"where's the closest village", "FIND:village"},
            {"go back to where I died", "GOTO:death"}, {"go to my bed", "GOTO:bed"}, {"cancel that ping", "PING_EDIT:cancel"},
            {"clear all pings", "PING_EDIT:clear"}, {"skip that one", "PING_EDIT:skip"}, {"do the tree first", "PING_EDIT:first:logs"},
            {"mark this", "MARK@LOOKED"}, {"ping that", "MARK@LOOKED"}, {"build a bridge", "BRIDGE@LOOKED"},
            {"chop that, and that, then come back", "GATHER:logs@LOOKED > GATHER:logs@LOOKED > COME@ME"},
            {"go there, then stay", "GOTO@LOOKED > STAY"}, {"save this as home", "WAYPOINT:home@LOOKED"},
            {"where is the nearest diamond", "FIND:diamond"}, {"chop 64 logs", "GATHER:logs:64"}};
        for (String[] t : c) assertEquals(t[1], PaUnderstand.read(t[0], "basil").brief(), t[0]);
    }
}
