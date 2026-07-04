package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The problem-solver: given a goal item ("iron sword"), it recursively works
 * out the whole dependency tree — what to gather, smelt, and craft, in order —
 * sourcing each part from the pack, then a nearby chest, then by producing it.
 * Instead of "I don't have the parts, I can't", it plans how to GET the parts.
 *
 * This is a pillar of the end goal: an autonomous AI that, told what it needs,
 * figures out how to obtain it from first principles and just goes.
 */
public final class CraftPlanner {

    /** How far to look in chests for missing parts. */
    public static final int CHEST_RADIUS = 24;

    private CraftPlanner() {}

    private record In(String token, int qty) {}
    private record Recipe(boolean smelt, String key, int yield, List<In> inputs) {}

    /** A finished plan: ordered jobs, a human narration, and any dead-ends. */
    public record Result(List<Job> jobs, List<String> narration, List<String> blockers) {}

    // token -> how to make it. Mirrors CraftGoal's recipes with concrete tokens
    // so the tree can be resolved. `key` is the CraftGoal recipe key or the
    // SmeltGoal canonical.
    private static final Map<String, Recipe> KB = new LinkedHashMap<>();
    private static final Map<String, GatherGoal.Kind> GATHERABLE = new HashMap<>();

    static {
        craft("planks", 4, in("log", 1));
        // token "stick" (how ingredients reference it); CraftGoal recipe key "sticks".
        craft("stick", "sticks", 4, in("planks", 2));
        craft("crafting table", 1, in("planks", 4));
        craft("chest", 1, in("planks", 8));
        craft("furnace", 1, in("cobblestone", 8));
        craft("torches", 4, in("coal", 1), in("stick", 1));
        craft("ladders", 3, in("stick", 7));
        smelt("iron ingot", "iron", in("raw iron", 1));
        craft("wooden pickaxe", 1, in("planks", 3), in("stick", 2));
        craft("wooden axe", 1, in("planks", 3), in("stick", 2));
        craft("wooden shovel", 1, in("planks", 1), in("stick", 2));
        craft("wooden hoe", 1, in("planks", 2), in("stick", 2));
        craft("wooden sword", 1, in("planks", 2), in("stick", 1));
        craft("stone pickaxe", 1, in("cobblestone", 3), in("stick", 2));
        craft("stone axe", 1, in("cobblestone", 3), in("stick", 2));
        craft("stone shovel", 1, in("cobblestone", 1), in("stick", 2));
        craft("stone hoe", 1, in("cobblestone", 2), in("stick", 2));
        craft("stone sword", 1, in("cobblestone", 2), in("stick", 1));
        craft("iron pickaxe", 1, in("iron ingot", 3), in("stick", 2));
        craft("iron axe", 1, in("iron ingot", 3), in("stick", 2));
        craft("iron shovel", 1, in("iron ingot", 1), in("stick", 2));
        craft("iron hoe", 1, in("iron ingot", 2), in("stick", 2));
        craft("iron sword", 1, in("iron ingot", 2), in("stick", 1));
        craft("iron helmet", 1, in("iron ingot", 5));
        craft("iron chestplate", 1, in("iron ingot", 8));
        craft("iron leggings", 1, in("iron ingot", 7));
        craft("iron boots", 1, in("iron ingot", 4));
        craft("shears", 1, in("iron ingot", 2));
        craft("bucket", 1, in("iron ingot", 3));
        craft("bow", 1, in("stick", 3), in("string", 3));
        craft("arrows", 4, in("flint", 1), in("stick", 1), in("feather", 1));
        craft("fishing rod", 1, in("stick", 3), in("string", 2));
        craft("bread", 1, in("wheat", 3));

        GATHERABLE.put("log", GatherGoal.Kind.LOGS);
        GATHERABLE.put("cobblestone", GatherGoal.Kind.STONE);
        GATHERABLE.put("stone", GatherGoal.Kind.STONE);
        GATHERABLE.put("raw iron", GatherGoal.Kind.IRON);
        GATHERABLE.put("coal", GatherGoal.Kind.COAL);
        GATHERABLE.put("dirt", GatherGoal.Kind.DIRT);
    }

    private static In in(String token, int qty) { return new In(token, qty); }
    private static void craft(String token, int yield, In... inputs) {
        KB.put(token, new Recipe(false, token, yield, List.of(inputs)));
    }
    private static void craft(String token, String key, int yield, In... inputs) {
        KB.put(token, new Recipe(false, key, yield, List.of(inputs)));
    }
    private static void smelt(String token, String canonical, In... inputs) {
        KB.put(token, new Recipe(true, canonical, 1, List.of(inputs)));
    }

    /** Longest KB target whose words all appear in the clause (plural slop). */
    @javax.annotation.Nullable
    public static String matchTarget(String clause) {
        String best = null;
        for (String key : KB.keySet()) {
            boolean all = true;
            for (String w : key.split(" ")) {
                String noS = w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
                String noEs = w.endsWith("es") ? w.substring(0, w.length() - 2) : noS;
                if (!clause.contains(w) && !clause.contains(noS) && !clause.contains(noEs)) {
                    all = false;
                    break;
                }
            }
            if (all && (best == null || key.length() > best.length())) best = key;
        }
        return best;
    }

    public static Result plan(AssistantEntity a, String target, int amount) {
        List<Job> jobs = new ArrayList<>();
        List<String> narration = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<Container> chests = a.nearbyContainers(CHEST_RADIUS);
        obtain(a, chests, target, Math.max(1, amount), jobs, narration, blockers,
            new HashMap<>(), new HashMap<>());
        return new Result(jobs, narration, blockers);
    }

    private static void obtain(AssistantEntity a, List<Container> chests, String token, int qty,
                               List<Job> jobs, List<String> narration, List<String> blockers,
                               Map<String, Integer> resPack, Map<String, Integer> resChest) {
        Predicate<ItemStack> m = matcher(token);

        int packLeft = a.countMatching(m) - resPack.getOrDefault(token, 0);
        int usePack = Math.min(qty, Math.max(0, packLeft));
        if (usePack > 0) {
            resPack.merge(token, usePack, Integer::sum);
            qty -= usePack;
        }
        if (qty <= 0) return;

        int chestLeft = countIn(chests, m) - resChest.getOrDefault(token, 0);
        if (chestLeft > 0) {
            int w = Math.min(qty, chestLeft);
            resChest.merge(token, w, Integer::sum);
            jobs.add(Job.withdraw(token, w));
            narration.add("grab " + w + " " + token + " from the chest");
            qty -= w;
        }
        if (qty <= 0) return;

        Recipe r = KB.get(token);
        if (r == null) {
            GatherGoal.Kind k = GATHERABLE.get(token);
            if (k != null) {
                jobs.add(Job.gather(k, qty));
                narration.add("gather " + qty + " " + k.label);
            } else {
                blockers.add(qty + " " + token);
            }
            return;
        }
        int crafts = (qty + r.yield() - 1) / r.yield();
        for (In input : r.inputs()) {
            obtain(a, chests, input.token(), input.qty() * crafts, jobs, narration, blockers, resPack, resChest);
        }
        if (r.smelt()) {
            jobs.add(Job.smelt(r.key(), crafts));
            narration.add("smelt " + crafts + " " + r.key());
        } else {
            jobs.add(Job.craft(r.key(), crafts));
            narration.add("craft " + r.key() + (crafts > 1 ? " x" + crafts : ""));
        }
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

    /** Precise item test for counting a planner token. */
    static Predicate<ItemStack> matcher(String t) {
        return switch (t) {
            case "log" -> s -> s.is(ItemTags.LOGS);
            case "planks" -> s -> s.is(ItemTags.PLANKS);
            case "stick" -> s -> s.is(Items.STICK);
            case "cobblestone", "stone" -> s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
            case "coal" -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            case "dirt" -> s -> s.is(Items.DIRT);
            case "raw iron" -> s -> s.is(Items.RAW_IRON);
            case "iron ingot" -> s -> s.is(Items.IRON_INGOT);
            case "string" -> s -> s.is(Items.STRING);
            case "feather" -> s -> s.is(Items.FEATHER);
            case "flint" -> s -> s.is(Items.FLINT);
            case "wheat" -> s -> s.is(Items.WHEAT);
            default -> {
                String path = t.replace(' ', '_');
                yield s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(path);
            }
        };
    }
}
