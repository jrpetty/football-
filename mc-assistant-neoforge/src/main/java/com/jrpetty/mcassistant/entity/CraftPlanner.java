package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The problem-solver. Given a goal item ("iron sword", "piston", "hopper"), it
 * reads the real recipe from {@link RecipeBook} and recursively works out the
 * whole dependency tree — what to gather, smelt, and craft, in order — sourcing
 * each part from the pack, then a nearby chest, then by producing it from first
 * principles. Instead of "I don't have the parts, I can't", it plans how to GET
 * them, and only reports a true dead-end (a leaf it can't gather or find) as a
 * blocker to fetch from a chest.
 *
 * A pillar of the end goal: an autonomous AI that, told what it needs, figures
 * out how to obtain it and just goes.
 */
public final class CraftPlanner {

    /** How far to look in chests for missing parts. */
    public static final int CHEST_RADIUS = 24;

    private static final int MAX_DEPTH = 12;

    private CraftPlanner() {}

    /** A finished plan: ordered jobs, a human narration, and any dead-ends. */
    public record Result(List<Job> jobs, List<String> narration, List<String> blockers) {}

    // ---- entry points ----

    /** Resolve a spoken clause to a target id (or abstract base), or null. */
    @Nullable
    public static String matchTarget(String clause) {
        return RecipeBook.resolvePath(clause);
    }

    /** Natural-language name for a target ("iron_sword" -> "iron sword"). */
    public static String pretty(String target) {
        if (target.equals("planks") || target.equals("sticks")) return target;
        Item it = RecipeBook.item(target);
        return it == Items.AIR ? target : RecipeBook.words(it);
    }

    public static Result plan(AssistantEntity a, String target, int amount) {
        List<Job> jobs = new ArrayList<>();
        List<String> narration = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<Container> chests = a.nearbyContainers(CHEST_RADIUS);
        Item item = "planks".equals(target) ? Items.OAK_PLANKS
                  : "sticks".equals(target) ? Items.STICK
                  : RecipeBook.item(target);
        if (item == Items.AIR) {
            blockers.add(target);
            return new Result(jobs, narration, blockers);
        }
        // For the abstract wood targets, force base handling.
        boolean abstractPlanks = "planks".equals(target);
        obtain(a, chests, item, Math.max(1, amount), jobs, narration, blockers,
            new HashMap<>(), new HashMap<>(), new HashSet<>(), 0, abstractPlanks);
        dedupe(jobs);
        return new Result(jobs, narration, blockers);
    }

    /** Collapse redundant sourcing trips: one mine per depth (a single shaft
     *  finds every ore at that level), one hunt per animal (largest count wins). */
    private static void dedupe(List<Job> jobs) {
        Map<String, Integer> huntMax = new HashMap<>();
        for (Job j : jobs) {
            if (j.type() == Job.Type.HUNT && j.arg() != null) {
                huntMax.merge(j.arg(), j.amount(), Math::max);
            }
        }
        Set<Integer> seenY = new HashSet<>();
        Set<String> seenHunt = new HashSet<>();
        List<Job> out = new ArrayList<>();
        for (Job j : jobs) {
            if (j.type() == Job.Type.MINE) {
                if (seenY.add(j.amount())) out.add(j);
            } else if (j.type() == Job.Type.HUNT && j.arg() != null) {
                if (seenHunt.add(j.arg())) out.add(Job.hunt(j.arg(), huntMax.get(j.arg())));
            } else {
                out.add(j);
            }
        }
        jobs.clear();
        jobs.addAll(out);
    }

    // ---- base materials: things we can make/gather from first principles ----

    private enum Base { LOG, PLANKS, STICK, COBBLE, COAL, RAW_IRON, IRON_INGOT, GOLD_INGOT, COPPER_INGOT, DIRT }

    @Nullable
    private static Base baseOf(Item it) {
        ItemStack s = new ItemStack(it);
        if (s.is(ItemTags.LOGS)) return Base.LOG;
        if (s.is(ItemTags.PLANKS)) return Base.PLANKS;
        if (it == Items.STICK) return Base.STICK;
        // Cobblestone only — actual "stone" is a smelt of cobble (see smeltOf).
        if (it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE) return Base.COBBLE;
        if (it == Items.COAL || it == Items.CHARCOAL) return Base.COAL;
        if (it == Items.RAW_IRON) return Base.RAW_IRON;
        if (it == Items.IRON_INGOT) return Base.IRON_INGOT;
        if (it == Items.GOLD_INGOT) return Base.GOLD_INGOT;
        if (it == Items.COPPER_INGOT) return Base.COPPER_INGOT;
        if (it == Items.DIRT) return Base.DIRT;
        return null;
    }

    private static Predicate<ItemStack> basePred(Base b) {
        return switch (b) {
            case LOG -> s -> s.is(ItemTags.LOGS);
            case PLANKS -> s -> s.is(ItemTags.PLANKS);
            case STICK -> s -> s.is(Items.STICK);
            case COBBLE -> s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
            case COAL -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            case RAW_IRON -> s -> s.is(Items.RAW_IRON);
            case IRON_INGOT -> s -> s.is(Items.IRON_INGOT);
            case GOLD_INGOT -> s -> s.is(Items.GOLD_INGOT);
            case COPPER_INGOT -> s -> s.is(Items.COPPER_INGOT);
            case DIRT -> s -> s.is(Items.DIRT);
        };
    }

    /** A smeltable we can produce from a base/gatherable input. */
    private record Smelt(String canonical, Item input) {}

    @Nullable
    private static Smelt smeltOf(Item it) {
        if (it == Items.GLASS) return new Smelt("sand", Items.SAND);
        if (it == Items.STONE) return new Smelt("stone", Items.COBBLESTONE);
        if (it == Items.CHARCOAL) return new Smelt("logs", Items.OAK_LOG);
        if (it == Items.COOKED_BEEF) return new Smelt("beef", Items.BEEF);
        if (it == Items.COOKED_PORKCHOP) return new Smelt("porkchop", Items.PORKCHOP);
        if (it == Items.COOKED_CHICKEN) return new Smelt("chicken", Items.CHICKEN);
        if (it == Items.COOKED_MUTTON) return new Smelt("mutton", Items.MUTTON);
        if (it == Items.COOKED_RABBIT) return new Smelt("rabbit", Items.RABBIT);
        if (it == Items.COOKED_COD) return new Smelt("fish", Items.COD);
        if (it == Items.COOKED_SALMON) return new Smelt("fish", Items.SALMON);
        if (it == Items.BAKED_POTATO) return new Smelt("potato", Items.POTATO);
        return null;
    }

    // ---- the recursion ----

    private static void obtain(AssistantEntity a, List<Container> chests, Item item, int qty,
                               List<Job> jobs, List<String> narration, List<String> blockers,
                               Map<String, Integer> resPack, Map<String, Integer> resChest,
                               Set<Item> path, int depth, boolean forceBase) {
        if (qty <= 0) return;
        Base base = forceBase ? Base.PLANKS : baseOf(item);
        Predicate<ItemStack> m = base != null ? basePred(base) : exact(item);
        String key = base != null ? "base:" + base.name() : "item:" + id(item);

        // 1) Use what's already in the pack.
        int packLeft = a.countMatching(m) - resPack.getOrDefault(key, 0);
        int usePack = Math.min(qty, Math.max(0, packLeft));
        if (usePack > 0) {
            resPack.merge(key, usePack, Integer::sum);
            qty -= usePack;
        }
        if (qty <= 0) return;

        // 2) Pull the rest from a nearby chest.
        int chestLeft = countIn(chests, m) - resChest.getOrDefault(key, 0);
        if (chestLeft > 0) {
            int w = Math.min(qty, chestLeft);
            resChest.merge(key, w, Integer::sum);
            String word = base != null ? baseWord(base) : RecipeBook.words(item);
            jobs.add(Job.withdraw(word, w));
            narration.add("grab " + w + " " + word + " from the chest");
            qty -= w;
        }
        if (qty <= 0) return;

        // 3) Produce it.
        if (base != null) {
            produceBase(a, chests, base, qty, jobs, narration, blockers, resPack, resChest, path, depth);
            return;
        }
        if (depth >= MAX_DEPTH || path.contains(item)) {
            blockers.add(qty + " " + RecipeBook.words(item));
            return;
        }
        RecipeBook.Craft craft = RecipeBook.craftFor(a.level(), item);
        if (craft != null) {
            int crafts = ceil(qty, craft.yield());
            path.add(item);
            for (RecipeBook.Part part : craft.parts()) {
                Item rep = chooseRep(a, chests, part);
                if (rep == Items.AIR) { blockers.add(part.label()); continue; }
                obtain(a, chests, rep, part.count() * crafts, jobs, narration, blockers,
                    resPack, resChest, path, depth + 1, false);
            }
            path.remove(item);
            jobs.add(Job.craft(id(item), crafts));
            narration.add("craft " + RecipeBook.words(item) + (crafts > 1 ? " x" + crafts : ""));
            return;
        }
        Smelt smelt = smeltOf(item);
        if (smelt != null) {
            obtain(a, chests, smelt.input(), qty, jobs, narration, blockers,
                resPack, resChest, path, depth + 1, false);
            jobs.add(Job.smelt(smelt.canonical(), qty));
            narration.add("smelt " + qty + " " + smelt.canonical());
            return;
        }
        // Last resort: go GET it from the world instead of giving up —
        // mine ores at depth, hunt animals for their drops, or gather blocks.
        int my = mineY(item);
        if (my != NO_MINE) {
            jobs.add(Job.mine(my));
            narration.add("mine down to Y" + my + " for " + RecipeBook.words(item));
            return;
        }
        String animal = huntFor(item);
        if (animal != null) {
            int n = Math.max(2, Math.min(16, qty));
            jobs.add(Job.hunt(animal, n));
            narration.add("hunt " + n + " " + animal + " for " + RecipeBook.words(item));
            return;
        }
        GatherGoal.Kind gk = gatherFor(item);
        if (gk != null) {
            jobs.add(Job.gather(gk, qty));
            narration.add("gather " + qty + " " + gk.label);
            return;
        }
        blockers.add(qty + " " + RecipeBook.words(item));
    }

    private static void produceBase(AssistantEntity a, List<Container> chests, Base base, int qty,
                                    List<Job> jobs, List<String> narration, List<String> blockers,
                                    Map<String, Integer> resPack, Map<String, Integer> resChest,
                                    Set<Item> path, int depth) {
        switch (base) {
            case LOG -> { jobs.add(Job.gather(GatherGoal.Kind.LOGS, qty)); narration.add("gather " + qty + " logs"); }
            case COBBLE -> { jobs.add(Job.gather(GatherGoal.Kind.STONE, qty)); narration.add("gather " + qty + " stone"); }
            case COAL -> { jobs.add(Job.gather(GatherGoal.Kind.COAL, qty)); narration.add("gather " + qty + " coal"); }
            case DIRT -> { jobs.add(Job.gather(GatherGoal.Kind.DIRT, qty)); narration.add("gather " + qty + " dirt"); }
            case RAW_IRON -> { jobs.add(Job.gather(GatherGoal.Kind.IRON, qty)); narration.add("gather " + qty + " raw iron"); }
            case PLANKS -> {
                int crafts = ceil(qty, 4);
                obtain(a, chests, Items.OAK_LOG, crafts, jobs, narration, blockers, resPack, resChest, path, depth + 1, false);
                jobs.add(Job.craft("planks", crafts));
                narration.add("craft planks" + (crafts > 1 ? " x" + crafts : ""));
            }
            case STICK -> {
                int crafts = ceil(qty, 4);
                obtain(a, chests, Items.OAK_PLANKS, crafts * 2, jobs, narration, blockers, resPack, resChest, path, depth + 1, false);
                jobs.add(Job.craft("sticks", crafts));
                narration.add("craft sticks" + (crafts > 1 ? " x" + crafts : ""));
            }
            case IRON_INGOT -> {
                obtain(a, chests, Items.RAW_IRON, qty, jobs, narration, blockers, resPack, resChest, path, depth + 1, false);
                jobs.add(Job.smelt("iron", qty));
                narration.add("smelt " + qty + " iron");
            }
            // Gold/copper: obtain the raw metal (from pack, chest, or by mining it
            // at depth), then smelt. No more dead-end — it goes and digs for it.
            case GOLD_INGOT -> {
                obtain(a, chests, Items.RAW_GOLD, qty, jobs, narration, blockers, resPack, resChest, path, depth + 1, false);
                jobs.add(Job.smelt("gold", qty));
                narration.add("smelt " + qty + " gold");
            }
            case COPPER_INGOT -> {
                obtain(a, chests, Items.RAW_COPPER, qty, jobs, narration, blockers, resPack, resChest, path, depth + 1, false);
                jobs.add(Job.smelt("copper", qty));
                narration.add("smelt " + qty + " copper");
            }
        }
    }

    /** Pick the best concrete item for an ingredient: have-it > base > oak > first. */
    private static Item chooseRep(AssistantEntity a, List<Container> chests, RecipeBook.Part part) {
        ItemStack[] items = part.ingredient().getItems();
        if (items.length == 0) return Items.AIR;
        Item avail = null;
        int availCount = 0;
        Item base = null;
        Item oak = null;
        for (ItemStack st : items) {
            Item it = st.getItem();
            Predicate<ItemStack> pm = exact(it);
            int have = a.countMatching(pm) + countIn(chests, pm);
            if (have > availCount) { availCount = have; avail = it; }
            if (base == null && baseOf(it) != null) base = it;
            if (oak == null && id(it).startsWith("oak")) oak = it;
        }
        if (avail != null) return avail;
        if (base != null) return base;
        if (oak != null) return oak;
        return items[0].getItem();
    }

    // ---- sourcing from the world (mine / hunt / gather) ----

    private static final int NO_MINE = Integer.MIN_VALUE;

    /** Best Y to branch-mine for an ore drop, or NO_MINE if it isn't mined. A
     *  single shaft at this depth chases whatever veins it passes. */
    private static int mineY(Item it) {
        if (it == Items.DIAMOND || it == Items.REDSTONE || it == Items.LAPIS_LAZULI) return -59;
        if (it == Items.RAW_GOLD) return -16;
        if (it == Items.RAW_COPPER) return 48;
        return NO_MINE;
    }

    /** Which passive animal drops this item (hunt them for it), or null. */
    @Nullable
    private static String huntFor(Item it) {
        if (it == Items.LEATHER || it == Items.BEEF) return "cow";
        if (it == Items.PORKCHOP) return "pig";
        if (it == Items.CHICKEN || it == Items.FEATHER) return "chicken";
        if (it == Items.MUTTON) return "sheep";
        if (it == Items.RABBIT || it == Items.RABBIT_HIDE) return "rabbit";
        if (new ItemStack(it).is(ItemTags.WOOL)) return "sheep";
        return null;
    }

    /** A directly-gatherable block for this item, or null. */
    @Nullable
    private static GatherGoal.Kind gatherFor(Item it) {
        if (it == Items.SAND || it == Items.RED_SAND) return GatherGoal.Kind.SAND;
        if (it == Items.GRAVEL) return GatherGoal.Kind.GRAVEL;
        if (it == Items.SUGAR_CANE) return GatherGoal.Kind.SUGAR_CANE; // -> paper -> books -> shelves
        return null;
    }

    // ---- helpers ----

    private static Predicate<ItemStack> exact(Item it) {
        return s -> s.is(it);
    }

    private static String id(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).getPath();
    }

    private static String baseWord(Base b) {
        return switch (b) {
            case LOG -> "logs";
            case PLANKS -> "planks";
            case STICK -> "sticks";
            case COBBLE -> "cobblestone";
            case COAL -> "coal";
            case RAW_IRON -> "raw iron";
            case IRON_INGOT -> "iron ingot";
            case GOLD_INGOT -> "gold ingot";
            case COPPER_INGOT -> "copper ingot";
            case DIRT -> "dirt";
        };
    }

    private static int ceil(int n, int per) {
        return (n + per - 1) / Math.max(1, per);
    }

    private static int countIn(List<Container> chests, Predicate<ItemStack> m) {
        int n = 0;
        for (Container c : chests) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && m.test(s)) n += s.getCount();
            }
        }
        return n;
    }
}
