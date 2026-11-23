package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [pa33c-2b] Pings: what was pinged, what can be done with it, and the order each choice makes. A bare ping is only a
 * marker, never an order: it becomes one when a wheel option is picked, an order is said or typed within a few seconds
 * (its "this/that/there" is the ping), or the ping key is tapped again on it. Pure Java: the client decides the target
 * from the world, the server turns a choice into the same words a player would say, read by the same reader.
 */
public final class PaPings {

    private PaPings() {}

    /** What a ping lands on. */
    public enum Target { GROUND, TREE, ORE, STONE, MOB, ANIMAL, CHEST, CROP, BED, FURNACE, PLAYER, ASSISTANT, WATER, LAVA, AREA, VILLAGER, TABLE }

    /** The kind of thing an option is, for its colour and shape (colour-blind safe: each has its own shape too). */
    public enum Sort {
        MOVE(0xFF4A90E2, "circle"), ATTACK(0xFFE5484D, "diamond"), GATHER(0xFF30A46C, "square"), DANGER(0xFFF08C00, "triangle"),
        LOOK(0xFFB8C4C2, "ring"), BUILD(0xFF8E6BD9, "hexagon");
        public final int colour;
        public final String shape;
        Sort(int colour, String shape) { this.colour = colour; this.shape = shape; }
    }

    /** One wheel option: an id, what it says on the wheel, its sort, and the words it sends ("" for special ones). */
    public record Option(String id, String label, Sort sort, String says) {}

    /** How long a bare ping waits for an order said, typed, chosen or tapped (ms). */
    public static final long WINDOW_MS = 5000;
    /** How long a bare ping's marker stays (ms). */
    public static final long MARK_MS = 30000;

    public static Target of(String s) {
        try {
            return Target.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Target.GROUND;
        }
    }

    /** The wheel for a target: its own options first, then the ones always there. */
    public static List<Option> options(Target t) {
        List<Option> o = new ArrayList<>();
        switch (t) {
            case GROUND -> {
                o.add(new Option("go", "Go there", Sort.MOVE, "go there"));
                o.add(new Option("wait", "Wait there", Sort.MOVE, "go there, then stay"));
                o.add(new Option("guard", "Guard here", Sort.MOVE, "guard there"));
                o.add(new Option("house", "Build a house here", Sort.BUILD, "build a small house there"));
                o.add(new Option("farmhere", "Make a field here", Sort.BUILD, "build a farm there"));
                o.add(new Option("light", "Light it up", Sort.GATHER, "light up the area"));
                o.add(new Option("down", "Dig down here", Sort.GATHER, "go there, then dig down"));
                o.add(new Option("trick", "Trick shot at it", Sort.ATTACK, "shoot that spot"));      // [pa33d]
                o.add(new Option("area", "Mark an area", Sort.BUILD, ""));
            }
            case TREE -> {
                o.add(new Option("chop", "Chop that", Sort.GATHER, "chop this tree"));
                o.add(new Option("grove", "Chop the whole grove", Sort.GATHER, "chop 64 logs"));
            }
            case ORE -> {
                o.add(new Option("vein", "Mine that (the whole vein)", Sort.GATHER, "mine this vein"));
                o.add(new Option("tunnel", "Mine a tunnel this way", Sort.GATHER, "mine a tunnel this way"));
            }
            case STONE -> {
                o.add(new Option("stone", "Mine that", Sort.GATHER, "mine this vein"));
                o.add(new Option("down", "Dig down here", Sort.GATHER, "go there, then dig down"));
            }
            case MOB -> {
                o.add(new Option("attack", "Attack that", Sort.ATTACK, "attack that"));
                o.add(new Option("shoot", "Shoot that", Sort.ATTACK, "shoot that"));
                o.add(new Option("leave", "Leave it alone", Sort.LOOK, ""));
            }
            case ANIMAL -> {
                o.add(new Option("hunt", "Hunt it", Sort.ATTACK, "attack that"));
                o.add(new Option("breed", "Breed them", Sort.GATHER, "breed them"));
                o.add(new Option("tame", "Tame it for me", Sort.GATHER, "tame that"));               // [pa33d]
                o.add(new Option("leave", "Leave it alone", Sort.LOOK, ""));
            }
            case CHEST -> {
                o.add(new Option("put", "Put stuff in there", Sort.GATHER, "put everything in that chest"));
                o.add(new Option("fetch", "Fetch from there", Sort.GATHER, "take everything from that chest"));
                o.add(new Option("sort", "Sort that", Sort.GATHER, "sort the chests"));
            }
            case CROP -> {
                o.add(new Option("farm", "Farm this", Sort.GATHER, "farm this field"));
                o.add(new Option("harvest", "Harvest and replant", Sort.GATHER, "harvest this field"));
            }
            case BED -> o.add(new Option("bed", "Use this bed", Sort.MOVE, "use this bed"));
            case FURNACE -> {
                o.add(new Option("fuel", "Fuel that", Sort.GATHER, "fuel the furnace"));
                o.add(new Option("smelt", "Smelt my ore there", Sort.GATHER, "smelt in that furnace"));
            }
            case PLAYER -> {
                o.add(new Option("hi", "Say hi", Sort.LOOK, ""));
                o.add(new Option("followthem", "Follow them", Sort.MOVE, ""));
            }
            case ASSISTANT -> o.add(new Option("hi", "Say hi", Sort.LOOK, ""));
            case WATER -> {
                o.add(new Option("bridge", "Bridge over", Sort.BUILD, "build a bridge"));
                o.add(new Option("go", "Go there", Sort.MOVE, "go there"));
            }
            case LAVA -> {
                o.add(new Option("danger", "Danger here", Sort.DANGER, ""));
                o.add(new Option("bridge", "Bridge over", Sort.BUILD, "build a bridge"));
            }
            case VILLAGER -> {                                                                         // [pa33d]
                o.add(new Option("trade", "Trade with them", Sort.GATHER, "trade with that villager"));
                o.add(new Option("hi", "Say hi", Sort.LOOK, ""));
            }
            case TABLE -> {
                o.add(new Option("enchant", "Enchant my gear here", Sort.BUILD, "enchant your gear"));
                o.add(new Option("go", "Go there", Sort.MOVE, "go there"));
            }
            case AREA -> {
                o.add(new Option("clear", "Clear this area", Sort.GATHER, "clear this area"));
                o.add(new Option("field", "Farm this field", Sort.BUILD, "build a farm there"));
                o.add(new Option("lightall", "Light the whole area", Sort.GATHER, "light up the area"));
            }
        }
        o.add(new Option("look", "Look at this", Sort.LOOK, ""));
        if (t != Target.LAVA) o.add(new Option("danger", "Danger here", Sort.DANGER, ""));
        o.add(new Option("cancel", "Cancel ping", Sort.LOOK, ""));
        return dedupe(o);
    }

    private static List<Option> dedupe(List<Option> o) {
        List<Option> out = new ArrayList<>();
        for (Option x : o) if (out.stream().noneMatch(y -> y.id().equals(x.id()))) out.add(x);
        return out;
    }

    public static Option option(Target t, String id) {
        for (Option o : options(t)) if (o.id().equals(id)) return o;
        return null;
    }

    /** The default action of a quick tap (only when the player turned "Act on quick pings" on). */
    public static Option quick(Target t) {
        return options(t).get(0);
    }

    /** The marker's sort for a target before anything is chosen (neutral). */
    public static Sort bare() {
        return Sort.LOOK;
    }

    /**
     * Is an order said or typed now bound to the last bare ping? Within the window, and the words point at something
     * ("this", "that", "there", "it", "them", "here") or are an order with no place of its own.
     */
    public static boolean binds(long pingAtMs, long nowMs, String words) {
        if (pingAtMs <= 0 || nowMs - pingAtMs > WINDOW_MS || nowMs < pingAtMs) return false;
        return words != null && !words.isBlank();
    }

    /** How many places a sentence points at in turn ("that, and that, then come back" points twice). */
    public static int pointers(String words) {
        if (words == null) return 0;
        int n = 0;
        for (String w : words.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+"))
            if (w.equals("that") || w.equals("this") || w.equals("there") || w.equals("it") || w.equals("them") || w.equals("those")) n++;
        return n;
    }

    /** A ping-spam guard per player: one every quarter second, thirty a minute. */
    public static final class Rate {
        private final java.util.ArrayDeque<Long> at = new java.util.ArrayDeque<>();

        public boolean allow(long nowMs) {
            while (!at.isEmpty() && nowMs - at.peekFirst() > 60_000) at.pollFirst();
            if (!at.isEmpty() && nowMs - at.peekLast() < 250) return false;
            if (at.size() >= 30) return false;
            at.addLast(nowMs);
            return true;
        }
    }
}
