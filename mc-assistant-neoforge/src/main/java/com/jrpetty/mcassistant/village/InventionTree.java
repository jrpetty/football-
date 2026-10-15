package com.jrpetty.mcassistant.village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * [tech-tree] The inventions a town's scholars can work out, as data: twenty-two of them in four branches, each with
 * the age it waits for, what must be known first, what it costs in learning (the scholars' points) and in days at the
 * least, the paper, ink and books its notes use up, and the working model the scholars build to try it (real makings
 * out of the stores, never out of nothing). What each one does in the world is the game's (entity/Inventions and the
 * classes it hands to); this is only the tree, so the unit tests can walk it without the game.
 *
 * <p>The tree sits beside the city's research (entity/CityTree, the sixty-three civics the leader picks and the whole
 * town works at by its headcount): the civics are ways of running a town, these are machines, buildings and arms, and
 * they are worked out by the few who have the head for it, at their desks, in their own time.
 */
public final class InventionTree {

    private InventionTree() {}

    /** The ages, by their order (Villages.Age): a tree that waits on the Iron Age waits on ordinal 2. */
    public static final String[] AGES = { "the Wood Age", "the Stone Age", "the Iron Age", "the Diamond Age", "the Nether Age" };

    public enum Branch {
        AGRICULTURE("Agriculture", "the plough, the mills and the fields"),
        ENGINEERING("Engineering", "cranes, clocks, water and the press"),
        MILITARY("Military", "new arms and armour, and siege craft"),
        LEARNING("Learning", "the counting house, physic, lenses and the sea");

        public final String title, about;

        Branch(String title, String about) {
            this.title = title;
            this.about = about;
        }
    }

    /**
     * One invention. {@code model} is the working model's makings, "item*count" by id (or "#planks", "#wool",
     * "#coals", "#logs" for any of a kind), comma-separated; {@code building} the drawing it lets the town raise, or
     * null; {@code unlocks} what it opens in a few words, {@code effect} what it does, plainly.
     */
    public enum Invention {
        // ---- Agriculture: the plough, the mills, the fields.
        HEAVY_PLOUGH(Branch.AGRICULTURE, 0, "The Heavy Plough", 18, 2, "", "#planks*3,minecraft:stick*2,minecraft:cobblestone*2", 1, 0, 0,
            null, "a heavy plough for the fields", "one harvested crop in eight gives one more"),
        WINDMILL(Branch.AGRICULTURE, 1, "The Windmill's Millstones", 30, 2, "HEAVY_PLOUGH", "#planks*4,#wool*3,minecraft:stick*2", 2, 1, 0,
            "windmill", "the windmill, from a town of eight", "the windmill wanted from eight folk, and with a mill the bakery bakes three times the batch"),
        HORSE_COLLAR(Branch.AGRICULTURE, 1, "The Horse Collar", 35, 3, "HEAVY_PLOUGH", "minecraft:leather*2,minecraft:string*2,#planks*2", 2, 1, 0,
            null, "a padded collar for the plough team", "farmers work 6% quicker, ranchers 4%"),
        WATERMILL(Branch.AGRICULTURE, 2, "The Watermill", 60, 4, "WINDMILL", "#planks*6,minecraft:iron_ingot*2,minecraft:cobblestone*4", 3, 1, 1,
            "watermill", "the watermill", "its wheel grinds for the bakery and drives a trip-hammer: smith and smelter 8% quicker"),
        SEED_DRILL(Branch.AGRICULTURE, 3, "The Seed Drill", 100, 5, "HORSE_COLLAR,WATERMILL", "minecraft:iron_ingot*3,#planks*4,minecraft:wheat_seeds*8", 4, 2, 1,
            null, "seed sown in rows", "farmers 8% quicker, and one crop in five gives one more"),
        // ---- Engineering: cranes, clocks, water, the press.
        TREADWHEEL_CRANE(Branch.ENGINEERING, 1, "The Treadwheel Crane", 30, 2, "", "#planks*6,minecraft:string*4,minecraft:stick*4", 2, 1, 0,
            null, "a crane on every building site", "the builders lay their blocks 12% quicker"),
        TEMPERED_TOOLS(Branch.ENGINEERING, 1, "Tempered Tools", 35, 3, "", "minecraft:iron_ingot*2,#coals*2", 2, 1, 0,
            null, "tools hardened and tempered", "every trade 2% quicker, and one use of a tool in six spared its wear"),
        CLOCKTOWER(Branch.ENGINEERING, 2, "The Clocktower", 70, 4, "TREADWHEEL_CRANE", "minecraft:gold_ingot*4,minecraft:redstone*1", 3, 2, 1,
            "clocktower", "the clocktower", "while it stands the town keeps the hours: every trade 3% quicker, and a chime at every hour"),
        AQUEDUCT(Branch.ENGINEERING, 2, "The Aqueduct", 80, 5, "TREADWHEEL_CRANE", "minecraft:stone_bricks*8,minecraft:clay_ball*4", 3, 1, 1,
            "aqueduct", "the aqueduct", "while it stands: clean water (contentment +3, the hurt mend faster) and watered fields (one crop in ten more)"),
        MOVABLE_TYPE(Branch.ENGINEERING, 3, "Movable Type", 110, 5, "CLOCKTOWER", "minecraft:iron_ingot*4,#planks*4,minecraft:ink_sac*2", 6, 2, 2,
            null, "a printing press of movable type", "learning 20% quicker, and what trading partners know comes twice as fast"),
        BLACK_POWDER(Branch.ENGINEERING, 4, "Black Powder", 160, 6, "STEEL,MOVABLE_TYPE", "minecraft:gunpowder*8,minecraft:iron_ingot*2", 4, 2, 2,
            null, "blasting powder", "miners 10% quicker, and a powder keg blows a besieged gate in three blows"),
        // ---- Military: arms, armour and the siege.
        LONGBOW(Branch.MILITARY, 1, "The Longbow", 28, 2, "", "minecraft:stick*3,minecraft:string*4", 1, 1, 0,
            null, "longbows from the fletcher", "a longbow: its arrows fly a fifth further and strike a quarter harder"),
        PIKE(Branch.MILITARY, 1, "The Pike", 32, 3, "", "minecraft:iron_ingot*1,minecraft:stick*4", 1, 1, 0,
            null, "pikes from the smith", "a pike: a long reach, and half again as deadly to a rider"),
        WAR_HAMMER(Branch.MILITARY, 2, "The War Hammer", 50, 3, "PIKE", "minecraft:iron_ingot*3,minecraft:stick*2", 2, 1, 0,
            null, "war hammers from the smith", "a war hammer: slow, but it goes through armour"),
        STEEL(Branch.MILITARY, 2, "Steel", 75, 4, "PIKE", "minecraft:iron_ingot*4,#coals*4", 3, 1, 1,
            "foundry", "the foundry, steel, steel swords and armour", "the foundry turns iron and coal into steel; steel swords and steel armour, between iron and diamond"),
        ARBALEST(Branch.MILITARY, 2, "The Arbalest", 60, 3, "LONGBOW", "minecraft:crossbow*1,minecraft:iron_ingot*2", 2, 1, 0,
            null, "arbalests from the fletcher", "an arbalest: slow to span, but its bolt strikes half again as hard"),
        HALBERD(Branch.MILITARY, 2, "The Halberd", 55, 3, "STEEL", "mc_assistant:steel_ingot*2,minecraft:stick*2", 2, 1, 0,
            null, "halberds from the smith", "a halberd: reach, a steel edge, and a hook for the riders"),
        SIEGE_ENGINES(Branch.MILITARY, 3, "Siege Engines", 110, 5, "ARBALEST,STEEL", "#planks*8,minecraft:iron_ingot*4,minecraft:string*4", 4, 2, 1,
            null, "an iron-shod ram and mantlets", "a besieged gate falls in six blows, not ten, and the defenders take less heart from their walls"),
        // ---- Learning: the counting house, physic, lenses, the sea.
        DOUBLE_ENTRY(Branch.LEARNING, 2, "Double-Entry Ledgers", 60, 3, "", "minecraft:feather*2", 6, 2, 1,
            null, "the books kept twice over", "the town's takings 4% higher"),
        MEDICINE(Branch.LEARNING, 2, "Physic", 65, 4, "", "minecraft:glass_bottle*3,minecraft:bone_meal*4", 3, 1, 1,
            null, "a physician's learning", "the hurt mend faster, and the old live a tenth longer"),
        LENSES(Branch.LEARNING, 3, "Lenses", 100, 5, "MEDICINE", "minecraft:glass_pane*4,minecraft:gold_ingot*2", 3, 1, 1,
            null, "spectacles and the spyglass", "learning 25% quicker, and the scouts see a fifth further"),
        NAVIGATION(Branch.LEARNING, 3, "Navigation", 90, 4, "LENSES", "minecraft:compass*1,#planks*4", 4, 2, 1,
            null, "the compass and the chart", "the fishing fleet sails in the rain and fits out a boat more; a harbour may send ships further");

        public final Branch branch;
        /** The age it waits for, by ordinal (0 the Wood Age). */
        public final int age;
        public final String title;
        /** Learning to work it out, and the fewest days it can be done in (the model must be built and tried). */
        public final int cost, days;
        private final String needs;
        public final String model;
        public final int paper, ink, books;
        @Nullable public final String building;
        public final String unlocks, effect;

        Invention(Branch branch, int age, String title, int cost, int days, String needs, String model, int paper, int ink, int books,
                  @Nullable String building, String unlocks, String effect) {
            this.branch = branch;
            this.age = age;
            this.title = title;
            this.cost = cost;
            this.days = days;
            this.needs = needs;
            this.model = model;
            this.paper = paper;
            this.ink = ink;
            this.books = books;
            this.building = building;
            this.unlocks = unlocks;
            this.effect = effect;
        }

        /** For commands, the Ledger and the public API: "heavy_plough". */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** What must be known first (all of them). */
        public List<Invention> needs() {
            List<Invention> out = new ArrayList<>();
            if (needs.isEmpty()) return out;
            for (String n : needs.split(",")) out.add(Invention.valueOf(n.trim()));
            return out;
        }

        /** "the Iron Age". */
        public String ageWords() {
            return AGES[Math.max(0, Math.min(AGES.length - 1, age))];
        }

        /** Its tier for drawing the tree: the longest road of needs to it, from 1. */
        public int depth() {
            int d = 1;
            for (Invention n : needs()) d = Math.max(d, n.depth() + 1);
            return d;
        }

        /** The working model's makings, read: {item id or #kind, count}. */
        public List<Making> makings() {
            List<Making> out = new ArrayList<>();
            if (model.isEmpty()) return out;
            for (String part : model.split(",")) {
                String[] p = part.trim().split("\\*");
                out.add(new Making(p[0], p.length > 1 ? Integer.parseInt(p[1]) : 1));
            }
            return out;
        }
    }

    /** One of a model's makings: an item id ("minecraft:string") or a kind ("#planks"), and how many. */
    public record Making(String item, int count) {
        public boolean kind() {
            return item.startsWith("#");
        }

        /** "4 planks", "2 string", "2 sticks", "1 iron ingot". */
        public String words() {
            String name = (item.startsWith("#") ? item.substring(1) : item.substring(item.indexOf(':') + 1)).replace('_', ' ');
            boolean many = count > 1 && !name.endsWith("s") && !UNCOUNTED.contains(name);
            return count + " " + name + (many ? "s" : "");
        }
    }

    /** What is not counted one by one: "4 string", not "4 strings". */
    static final java.util.Set<String> UNCOUNTED = java.util.Set.of("string", "leather", "bone meal", "gunpowder", "redstone", "paper",
        "cobblestone", "wool", "coals", "planks", "logs");

    /** By its id ("heavy_plough"), its name ("HEAVY_PLOUGH") or its title ("the heavy plough"), any case; null if none. */
    @Nullable
    public static Invention byId(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        String k = id.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replace("'", "");
        for (Invention i : Invention.values()) if (i.name().equals(k)) return i;
        for (Invention i : Invention.values()) {
            String t = i.title.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replace("'", "");
            if (t.equals(k) || t.equals("THE_" + k) || t.equals(k.replaceFirst("^THE_", ""))) return i;
        }
        return null;
    }

    /** Is it open to a town that knows these, in this age: its age come, everything it needs known, not known itself? */
    public static boolean open(Invention i, Collection<Invention> known, int age) {
        if (known.contains(i) || age < i.age) return false;
        return known.containsAll(i.needs());
    }

    /** Days still to go: {@code left} learning (hundredths) at {@code perDay} (hundredths), never under the days the model still needs. */
    public static int daysLeft(int left, int perDay, int modelDaysLeft) {
        int byLearning = left <= 0 ? 0 : (left + Math.max(1, perDay) - 1) / Math.max(1, perDay);
        return Math.max(byLearning, Math.max(0, modelDaysLeft));
    }

    /** The whole tree's learning, for the balance tests: everything at once. */
    public static int totalCost() {
        int n = 0;
        for (Invention i : Invention.values()) n += i.cost;
        return n;
    }
}
