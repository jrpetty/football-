package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.CivicItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * [player-civic] What a master of each trade teaches an apprentice (PlayerTrades): three lessons, each a real piece
 * of the trade's work done with the master, and what each opens.
 *
 * <ul>
 * <li><b>The smith.</b> Bring twenty iron and watch a pickaxe forged (the pick is yours, the rest of the iron
 *     goes into the smithy's stores, as your keep); smelt sixteen iron at the forge; forge a reinforced pickaxe
 *     under the master's eye. The smith's made-to-order comes a sixth cheaper, then the reinforced pickaxe is
 *     yours to make, and at the last a quarter off.</li>
 * <li><b>The farmer.</b> Plant and harvest sixty-four wheat; bake sixteen loaves; bring in thirty-two roots.
 *     Your crops grow faster while you tend them, and one harvest in four gives one more.</li>
 * <li><b>The miner.</b> Mine ten iron ore with the master; twenty-four coal; two diamonds. One ore in five
 *     gives one more, and you dig quicker below ground.</li>
 * <li><b>The cave dweller.</b> Set sixteen torches below ground; put down ten monsters in the dark; mine sixteen
 *     ores deep down. You see in the dark deep underground, and one ore in five gives one more.</li>
 * <li><b>The enchanter.</b> Bring sixteen lapis and watch a book enchanted; enchant five things; make six
 *     bookshelves. A lapis back on every enchanting, then a level back as well.</li>
 * <li><b>The tailor.</b> Bring thirty-two wool and watch a bed made; sew four pieces of leather; weave four
 *     banners. The tailor's made-to-order a sixth cheaper, and your own leathers last a third longer.</li>
 * <li><b>The brewer.</b> Brew three potions; bring thirty-two wheat for the mash; brew four stouts of your own.
 *     Your potions last a quarter longer, then the brewer's stout is yours to brew.</li>
 * <li><b>The cook.</b> Cook sixteen meats or fish; bake sixteen loaves; bake four farmhouse pies. One dish in
 *     five comes out of the fire with one more, then the farmhouse pie is yours to bake.</li>
 * </ul>
 * The last lesson makes a Master: the master gives a graduation piece of its own make, with its mark on it.
 */
public final class Lessons {

    private Lessons() {}

    /** What a lesson asks for. */
    public enum Task {
        BRING("bring"), BREAK("dig or harvest"), CRAFT("make"), SMELT("smelt or cook"), BREW("brew"), ENCHANT("enchant"),
        KILL("put down"), PLACE("set");

        public final String verb;

        Task(String verb) { this.verb = verb; }

        /** Done out in the world with the master at its side (it walks with the apprentice). */
        public boolean outside() {
            return this == BREAK || this == KILL || this == PLACE;
        }
    }

    /**
     * One lesson: the task, how many, what the master says, what it opens, and what counts: a thing made, smelt,
     * brewed or brought (item), a block dug or set (block), a creature put down (mob). {@code deep}: below ground
     * only (a block or a creature under the sixtieth level with no sky over it).
     */
    public record Lesson(Task task, int count, String asks, String opens, @Nullable Predicate<ItemStack> item,
                         @Nullable Predicate<BlockState> block, @Nullable Predicate<Entity> mob, boolean deep) {}

    /** A trade's course: its word ("Smith"), the key its master's recipes carry, its three lessons, and its graduation piece. */
    public record Course(StationTask trade, String key, String word, List<Lesson> lessons, String gift) {

        public Lesson lesson(int done) {
            return lessons.get(Math.max(0, Math.min(lessons.size() - 1, done)));
        }

        /** "Apprentice Smith", "Journeyman Smith", "Master Smith" (or "" before the first lesson). */
        public String title(int done) {
            return done <= 0 ? "" : (done == 1 ? "Apprentice " : done == 2 ? "Journeyman " : "Master ") + word;
        }
    }

    /** The level a folk must have reached at its trade to take an apprentice. */
    public static final int MASTER = 25;

    private static final Map<StationTask, Course> COURSES = new EnumMap<>(StationTask.class);

    private static Lesson bring(int n, Predicate<ItemStack> what, String asks, String opens) {
        return new Lesson(Task.BRING, n, asks, opens, what, null, null, false);
    }

    private static Lesson make(Task t, int n, Predicate<ItemStack> what, String asks, String opens) {
        return new Lesson(t, n, asks, opens, what, null, null, false);
    }

    private static Lesson dig(int n, Predicate<BlockState> what, boolean deep, String asks, String opens) {
        return new Lesson(Task.BREAK, n, asks, opens, null, what, null, deep);
    }

    static boolean ripe(BlockState s, net.minecraft.world.level.block.Block crop) {
        return s.is(crop) && s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    static boolean leather(ItemStack s) {
        return s.is(Items.LEATHER_HELMET) || s.is(Items.LEATHER_CHESTPLATE) || s.is(Items.LEATHER_LEGGINGS) || s.is(Items.LEATHER_BOOTS);
    }

    static {
        COURSES.put(StationTask.SMITH, new Course(StationTask.SMITH, "smith", "Smith", List.of(
            bring(20, s -> s.is(Items.IRON_INGOT), "Bring me twenty iron ingots, and watch me forge a pickaxe.",
                "the smith's made-to-order a sixth cheaper"),
            make(Task.SMELT, 16, s -> s.is(Items.IRON_INGOT), "Work my forge with me: smelt sixteen iron.",
                "the reinforced pickaxe, yours to forge at any crafting table"),
            make(Task.CRAFT, 1, s -> s.is(CivicItems.REINFORCED_PICKAXE.get()), "Forge a reinforced pickaxe of your own, under my eye.",
                "a quarter off the smith's made-to-order")),
            "an iron axe of the master's forging"));
        COURSES.put(StationTask.FARM, new Course(StationTask.FARM, "farm", "Farmer", List.of(
            dig(64, s -> ripe(s, Blocks.WHEAT), false, "Plant and harvest sixty-four wheat, with me watching.",
                "crops that grow faster while you tend them"),
            make(Task.CRAFT, 16, s -> s.is(Items.BREAD), "Bake sixteen loaves of the harvest.",
                "one harvest in four gives one more"),
            dig(32, s -> ripe(s, Blocks.CARROTS) || ripe(s, Blocks.POTATOES) || ripe(s, Blocks.BEETROOTS), false,
                "Bring in a root harvest with me: thirty-two carrots, potatoes or beetroots.", "the farmer's own hoe")),
            "an iron hoe of the master's"));
        COURSES.put(StationTask.MINE, new Course(StationTask.MINE, "mine", "Miner", List.of(
            dig(10, s -> s.is(BlockTags.IRON_ORES), false, "Mine ten iron ore with me.", "one ore in five gives one more"),
            dig(24, s -> s.is(BlockTags.COAL_ORES), false, "Dig out twenty-four coal with me.", "quicker digging below ground"),
            dig(2, s -> s.is(BlockTags.DIAMOND_ORES), false, "Find diamonds: mine two diamond ore with me.", "the miner's own pick")),
            "an iron pickaxe of the master's"));
        COURSES.put(StationTask.CAVE, new Course(StationTask.CAVE, "cave", "Caver", List.of(
            new Lesson(Task.PLACE, 16, "Light the dark with me: set sixteen torches below ground.", "eyes for the dark, deep underground",
                null, s -> s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH), null, true),
            new Lesson(Task.KILL, 10, "Clear the dark: put down ten monsters below ground with me.", "one ore in five gives one more",
                null, null, e -> e instanceof Enemy, true),
            dig(16, s -> s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES), true, "Mine sixteen ores deep down, with me.",
                "the caver's own blade")),
            "an iron sword of the master's"));
        COURSES.put(StationTask.ENCHANT, new Course(StationTask.ENCHANT, "enchant", "Enchanter", List.of(
            bring(16, s -> s.is(Items.LAPIS_LAZULI), "Bring me sixteen lapis, and watch me enchant a book.",
                "a lapis back on every enchanting"),
            make(Task.ENCHANT, 5, s -> true, "Enchant five things at a table, under my eye.", "a level back on every enchanting"),
            make(Task.CRAFT, 6, s -> s.is(Items.BOOKSHELF), "Make me six bookshelves for a study.", "the enchanter's own book")),
            "a book the master enchanted"));
        COURSES.put(StationTask.TAILOR, new Course(StationTask.TAILOR, "tailor", "Tailor", List.of(
            bring(32, s -> s.is(ItemTags.WOOL), "Bring me thirty-two wool, and watch me make a bed.",
                "the tailor's made-to-order a sixth cheaper"),
            make(Task.CRAFT, 4, Lessons::leather, "Cut and sew four pieces of leather with me.", "your own leathers last a third longer"),
            make(Task.CRAFT, 4, s -> s.is(ItemTags.BANNERS), "Weave four banners.", "the tailor's own tunic")),
            "a leather tunic of the master's"));
        COURSES.put(StationTask.BREW, new Course(StationTask.BREW, "brew", "Brewer", List.of(
            make(Task.BREW, 3, s -> true, "Brew three potions with me.", "potions that last a quarter longer"),
            bring(32, s -> s.is(Items.WHEAT), "Bring me thirty-two wheat for the mash, and watch me brew.",
                "the brewer's stout, yours to brew at any crafting table"),
            make(Task.CRAFT, 4, s -> s.is(CivicItems.BREWERS_STOUT.get()), "Brew four stouts of your own.", "the brewer's own stouts")),
            "three of the master's stouts"));
        COURSES.put(StationTask.COOK, new Course(StationTask.COOK, "cook", "Cook", List.of(
            make(Task.SMELT, 16, s -> s.get(DataComponents.FOOD) != null, "Cook sixteen meats or fish with me.",
                "one dish in five comes out with one more"),
            make(Task.CRAFT, 16, s -> s.is(Items.BREAD), "Bake sixteen loaves with me.", "the farmhouse pie, yours to bake at any crafting table"),
            make(Task.CRAFT, 4, s -> s.is(CivicItems.FARMHOUSE_PIE.get()), "Bake four farmhouse pies.", "the cook's own pies")),
            "two of the master's farmhouse pies"));
    }

    /** The course a trade teaches, or null for a trade that takes no apprentices. */
    @Nullable
    public static Course of(StationTask t) {
        return COURSES.get(t);
    }

    @Nullable
    public static Course byKey(String key) {
        for (Course c : COURSES.values()) if (c.key().equals(key)) return c;
        return null;
    }

    public static java.util.Collection<Course> all() {
        return COURSES.values();
    }
}
