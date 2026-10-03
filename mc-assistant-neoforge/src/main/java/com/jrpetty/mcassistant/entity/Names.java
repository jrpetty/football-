package com.jrpetty.mcassistant.entity;

import java.util.List;
import java.util.UUID;

/**
 * The pool every specialist is named from. A crew of ten all called "assistant"
 * is unusable — you cannot tell the roster apart, and no order screen means
 * anything if every row reads the same.
 *
 * <p>Nothing in this mod is typed, so a name is always chosen from this list.
 * It lives in common code so the picker on the client and the rename on the
 * server agree on what index 17 means, and so an order carrying a name is an
 * index into a fixed list rather than a free string off the wire.
 */
public final class Names {

    private Names() {}

    /** Working names — short, sayable, and distinct at a glance in a list. */
    /** Ground names, cycled by right-clicking a patch on the map. Ends back
     *  at unnamed, so a mis-click is never permanent. */
    public static final List<String> PATCHES = List.of(
        "North Farm", "South Field", "East Wood", "West Mine",
        "The Pit", "The Orchard", "The Pens", "The Docks",
        "Home Fields", "The Frontier", "The Quarry", "The Grange",
        "The Hollow", "Millbrook", "Long Acre", "The Warren",
        "Cinder Row", "The Shallows", "High Meadow", "The Cut",
        "Foxglove", "The Yards", "The Terraces", "Old Boundary");

    public static final List<String> POOL = List.of(
        "Bramble", "Fen", "Holt", "Marrow", "Quill", "Rook",
        "Tansy", "Wick", "Bryn", "Cobb", "Dell", "Ember",
        "Flint", "Hazel", "Juniper", "Kestrel", "Larkin", "Mabel",
        "Nettle", "Orin", "Pike", "Reed", "Sorrel", "Thatch",
        "Vesper", "Willa", "Yarrow", "Ash", "Birch", "Corvin",
        "Dane", "Elm", "Ferris", "Gale", "Hollis", "Iris",
        "Kip", "Linden", "Moss", "Nell", "Otis", "Perrin",
        "Rowan", "Sage", "Teal", "Vale", "Wren", "Alder"
    );

    public static boolean validIndex(int i) {
        return i >= 0 && i < POOL.size();
    }

    public static String at(int i) {
        return POOL.get(Math.floorMod(i, POOL.size()));
    }

    /**
     * A name nobody on this crew is using yet. Walks the pool in order, so a
     * growing crew reads Bramble, Fen, Holt... rather than a random scatter,
     * and only falls back to numbering once the pool is genuinely exhausted.
     */
    public static String freeFor(UUID owner) {
        List<String> taken = owner == null ? List.of() : AssistantEntity.namesFor(owner);
        for (String candidate : POOL) {
            if (!containsIgnoreCase(taken, candidate)) return candidate;
        }
        for (int n = 2; n < 100; n++) {
            for (String candidate : POOL) {
                String numbered = candidate + n;
                if (!containsIgnoreCase(taken, numbered)) return numbered;
            }
        }
        return "Assistant";
    }

    /** Is this name already on the crew? Names are stored lower-cased. */
    public static boolean inUse(UUID owner, String name) {
        return owner != null && containsIgnoreCase(AssistantEntity.namesFor(owner), name);
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
