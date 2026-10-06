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
        "Rowan", "Sage", "Teal", "Vale", "Wren", "Alder",
        // More, so that a town of a hundred has a hundred names and no two towns sound alike: hedgerow and
        // meadow, birds and beasts, old names, trade names, the lie of the land, the weather, and a few
        // fond nonsenses. Only ever added to at the end: a rename order carries an index into this list.
        "Acorn", "Alyssum", "Angelica", "Anise", "Aspen", "Aster", "Basil", "Bay",
        "Betony", "Bilberry", "Bluebell", "Borage", "Briar", "Bryony", "Burdock", "Burnet",
        "Bracken", "Calla", "Campion", "Caraway", "Catkin", "Cedar", "Celandine", "Chervil",
        "Chestnut", "Chicory", "Cicely", "Clary", "Clove", "Clover", "Comfrey", "Cowslip",
        "Cress", "Crocus", "Cypress", "Daisy", "Damson", "Dill", "Dogwood", "Fennel",
        "Fern", "Feverfew", "Flax", "Furze", "Gorse", "Hawthorn", "Heath", "Heather",
        "Holly", "Hops", "Hyssop", "Ivy", "Jasmine", "Larch", "Laurel", "Lavender",
        "Lichen", "Lily", "Lovage", "Madder", "Mallow", "Maple", "Marigold", "Marjoram",
        "Meadow", "Medlar", "Mint", "Myrtle", "Olive", "Orris", "Osier", "Pansy",
        "Peony", "Periwinkle", "Pimpernel", "Plum", "Poppy", "Primrose", "Quince", "Rosemary",
        "Rue", "Rush", "Saffron", "Salvia", "Samphire", "Sedge", "Senna", "Sloe",
        "Sweetbriar", "Tarragon", "Teasel", "Thistle", "Thyme", "Tormentil", "Valerian", "Vervain",
        "Vetch", "Violet", "Walnut", "Willow", "Woad", "Woodruff", "Yew", "Hellebore",
        "Speedwell", "Harebell", "Bramley", "Russet", "Pippin", "Codlin", "Burr", "Sprig",
        "Twig", "Eyebright", "Lupin", "Snowdrop", "Wisteria", "Saxifrage", "Agrimony", "Finch",
        "Linnet", "Lark", "Merlin", "Plover", "Robin", "Starling", "Swift", "Thrush",
        "Raven", "Crane", "Heron", "Osprey", "Kite", "Jay", "Magpie", "Martin",
        "Dunnock", "Siskin", "Sparrow", "Tern", "Brock", "Otter", "Hare", "Stoat",
        "Vole", "Fawn", "Hart", "Roe", "Colt", "Moth", "Cricket", "Newt",
        "Perch", "Tench", "Minnow", "Trout", "Curlew", "Dipper", "Godwit", "Lapwing",
        "Nightjar", "Pipit", "Redwing", "Shrike", "Stonechat", "Whinchat", "Bunting", "Corncrake",
        "Fieldfare", "Ada", "Agnes", "Alba", "Alden", "Aldous", "Alfred", "Alice",
        "Alwin", "Amos", "Anselm", "Arden", "Arlo", "Arthur", "Barnaby", "Bart",
        "Beatrix", "Bede", "Benedict", "Bertram", "Blythe", "Bram", "Bridget", "Brin",
        "Cecily", "Cedric", "Clement", "Constance", "Cuthbert", "Cyril", "Dorcas", "Dunstan",
        "Edith", "Edmund", "Edric", "Edwin", "Eldon", "Elric", "Elspeth", "Emery",
        "Enid", "Esme", "Ethel", "Everard", "Ezra", "Florian", "Gareth", "Gerald",
        "Gideon", "Gilbert", "Godwin", "Greta", "Griselda", "Gwen", "Hamish", "Harold",
        "Hattie", "Hector", "Hilda", "Hob", "Horace", "Hugh", "Humphrey", "Ida",
        "Ines", "Isolde", "Ivo", "Jethro", "Joan", "Jory", "Josiah", "Jude",
        "Kenelm", "Lambert", "Leofric", "Leopold", "Lettice", "Lionel", "Lorna", "Lowell",
        "Mabry", "Maud", "Merrick", "Mervyn", "Milo", "Miriam", "Morwen", "Ned",
        "Nessa", "Ninian", "Odo", "Olwen", "Osbert", "Oswin", "Percival", "Petra",
        "Piers", "Prudence", "Quentin", "Ralph", "Ranulf", "Reynard", "Rhys", "Roland",
        "Rosalind", "Sabine", "Seth", "Sidony", "Silas", "Simeon", "Swithin", "Sybil",
        "Tabitha", "Tamsin", "Thaddeus", "Theda", "Tobias", "Tristan", "Ulric", "Ursula",
        "Vivian", "Walter", "Wilfred", "Winifred", "Wystan", "Yorick", "Zillah", "Aelric",
        "Bronwen", "Caddoc", "Dilys", "Eira", "Gethin", "Idris", "Iolo", "Meredith",
        "Rhian", "Tegan", "Aled", "Bevan", "Cadfael", "Elowen", "Jowan", "Kerensa",
        "Lowen", "Morwenna", "Pedrek", "Tressa", "Wenna", "Agatha", "Ambrose", "Barnabas",
        "Clemency", "Damaris", "Eustace", "Felix", "Gervase", "Hesper", "Jocelyn", "Leander",
        "Mercy", "Obadiah", "Peregrine", "Rosamund", "Thomasin", "Tybalt", "Archer", "Barley",
        "Barrow", "Beck", "Booker", "Bowman", "Brook", "Chandler", "Cooper", "Cotter",
        "Croft", "Crowther", "Dale", "Draper", "Dyer", "Fairfax", "Falconer", "Farrow",
        "Fenner", "Fletcher", "Ford", "Forrester", "Fowler", "Gage", "Garner", "Glover",
        "Granger", "Hale", "Harrow", "Hayward", "Hollins", "Hooper", "Kemp", "Kendal",
        "Knox", "Lowe", "Marsh", "Mercer", "Nash", "Oakes", "Parry", "Payne",
        "Penn", "Pickett", "Pryor", "Radley", "Reeve", "Ridley", "Royce", "Sawyer",
        "Shaw", "Slade", "Stroud", "Thackeray", "Thorne", "Tolliver", "Tucker", "Turner",
        "Vance", "Wade", "Webb", "Wells", "Weston", "Whitlock", "Wilde", "Wyatt",
        "Yardley", "Cairn", "Clough", "Combe", "Copse", "Crag", "Dune", "Glen",
        "Grove", "Knoll", "Lea", "Lynn", "Mere", "Moor", "Ness", "Rill",
        "Tarn", "Thorpe", "Tor", "Weald", "Wold", "Burrow", "Cliff", "Cove",
        "Hollow", "Ridge", "Brae", "Holm", "Strand", "Haven", "Firth", "Garth",
        "Hurst", "Shingle", "Autumn", "Dawn", "Dusk", "Frost", "Haze", "Mist",
        "Storm", "Winter", "Cinder", "Spark", "Soot", "Tallow", "Pebble", "Slate",
        "Shale", "Jet", "Garnet", "Amber", "Jasper", "Opal", "Pearl", "Beryl",
        "Coral", "Agate", "Onyx", "Flurry", "Drizzle", "Zephyr", "Gloaming", "Sundown",
        "Equinox", "Rime", "Hoar", "Button", "Bobbin", "Thimble", "Tuppence", "Farthing",
        "Penny", "Crumpet", "Dumpling", "Pudding", "Treacle", "Nutmeg", "Pepper", "Wicket",
        "Puddle", "Tumble", "Hobnail", "Tinker", "Scamp", "Shilling", "Toffee", "Muffin",
        "Fable", "Dapple", "Speckle", "Tuft", "Wisp", "Whistle", "Dimple", "Fiddle",
        "Piper", "Tabor", "Lute", "Harper", "Ballad", "Rhyme", "Sonnet", "Barnet",
        "Cobbett", "Fothergill", "Hornby", "Jessop", "Lockett", "Midgley", "Nuttall", "Pennock",
        "Quayle", "Snowden", "Tunstall", "Upton", "Waddell", "Ackroyd", "Blenkin", "Crabtree",
        "Dobbin", "Eckersley", "Hebden", "Lumb", "Mottram", "Ollerton", "Pilling", "Scholes",
        "Thwaite", "Aldo", "Bruno", "Cosmo", "Dino", "Enzo", "Fabian", "Guido",
        "Lucan", "Marius", "Nico", "Orsino", "Rocco", "Tito", "Ugo", "Vito"
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

    /**
     * A name nobody in this village is using, drawn at random from the whole pool, so one town's
     * founders are not the next town's (walking the pool in order, every town began Bramble, Fen,
     * Holt, Marrow). Numbered, as {@link #freeFor} does, only once every name is in use.
     */
    public static String freshFor(UUID village, net.minecraft.util.RandomSource random) {
        List<String> taken = village == null ? List.of() : AssistantEntity.namesFor(village);
        return pickFree(taken, random, () -> freeFor(village));
    }

    /** A name not in `used` (lower-cased names), drawn at random; it is added to `used`. */
    public static String freshAmong(java.util.Set<String> used, net.minecraft.util.RandomSource random) {
        String n = pickFree(new java.util.ArrayList<>(used), random, () -> "folk_" + (used.size() + 1));
        used.add(n.toLowerCase());
        return n;
    }

    private static String pickFree(List<String> taken, net.minecraft.util.RandomSource random, java.util.function.Supplier<String> otherwise) {
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String t : taken) lower.add(t.toLowerCase());
        int free = 0;
        for (String c : POOL) if (!lower.contains(c.toLowerCase())) free++;
        if (free == 0) return otherwise.get();
        int k = random.nextInt(free);
        for (String c : POOL) {
            if (lower.contains(c.toLowerCase())) continue;
            if (k-- == 0) return c;
        }
        return otherwise.get();
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
