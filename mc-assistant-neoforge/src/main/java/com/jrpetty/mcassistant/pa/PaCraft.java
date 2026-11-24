package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [pa36] Making anything on request, the way a player does: the server's own recipes (vanilla and modded, shaped and
 * shapeless, tags such as "any planks"), ingredients from its pack, from what its player hands it, and (with leave)
 * from its player's chests; the steps in between worked out (logs into planks into sticks; ore smelted into ingots);
 * and when something is missing, exactly what, with a choice: hand it over, let it go and get it, or leave it.
 *
 * <p>The crafting itself is the engine's (CraftPlanner plans, CraftGoal crafts at a table it finds or places), at the
 * hand pace PaPace sets. This class decides whether to start, says what's short, answers "how do I make a piston?",
 * fills the Craft view, and hands the result over ("make me ...") or puts it on ("make yourself ...").
 */
public final class PaCraft {

    private PaCraft() {}

    /** A craft waiting on its player: the shortfall to hand over, or the OK for rare things. */
    record Pending(String item, int n, Map<Item, Integer> missing, String said, boolean go) {}

    static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ recipes

    /** The item a phrase names, from every crafting recipe's result on this server (modded too), else the registry. */
    @Nullable
    public static Item itemFor(Level level, String phrase) {
        String p = phrase.toLowerCase(Locale.ROOT).trim();
        if (p.startsWith("item:")) return BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(p.substring(5)));
        String path = RecipeBook.resolvePath(p);
        if (path != null && !path.equals("planks") && !path.equals("sticks")) {
            Item it = RecipeBook.item(path);
            if (it != Items.AIR) return it;
        }
        if ("planks".equals(path)) return Items.OAK_PLANKS;
        if ("sticks".equals(path)) return Items.STICK;
        // modded results: "copper wrench" -> somemod:copper_wrench
        String want = p.replaceAll("^(?:a|an|the|some) ", "").replace(' ', '_');
        Item best = null;
        for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = h.value().getResultItem(level.registryAccess());
            if (out.isEmpty()) continue;
            String rp = BuiltInRegistries.ITEM.getKey(out.getItem()).getPath();
            if (rp.equals(want) || rp.equals(want.replaceAll("s$", ""))) return out.getItem();
            if (best == null && rp.contains(want)) best = out.getItem();
        }
        return best;
    }

    /** What it can lay hands on now: its pack, and its player's chests near by that it may open. */
    static Map<Item, Integer> available(PlayerAssistantEntity a, boolean chests) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        if (chests) {
            PlayerAssistantEntity before = PaChests.askAs(a);
            try {
                for (Container c : a.nearbyContainers(CraftPlanner.CHEST_RADIUS))
                    for (int i = 0; i < c.getContainerSize(); i++) { ItemStack s = c.getItem(i); if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum); }
            } finally {
                PaChests.askAs(before);
            }
        }
        return m;
    }

    /**
     * What is missing to make {@code n} of an item: the recipe's own parts it can't get or make from what it has (empty
     * when it can). Intermediates made from what's at hand don't count as missing; ore smelts into ingots.
     */
    public static Map<Item, Integer> missing(Level level, Item item, int n, Map<Item, Integer> avail) {
        Map<Item, Integer> first = null;
        for (RecipeBook.Way w : RecipeBook.waysFor(level, item)) {
            if (w.fire() != RecipeBook.Fire.NONE) continue;
            Map<Item, Integer> miss = missingBy(level, item, w, n, avail);
            if (miss.isEmpty()) return miss;
            if (first == null) first = miss;
        }
        if (first != null) return first;
        // no bench recipe: fired, or gathered
        Map<Item, Integer> pool = new LinkedHashMap<>(avail);
        Map<Item, Integer> miss = new LinkedHashMap<>();
        if (!makeable(level, item, n, pool, new HashSet<>(), 0)) miss.put(item, Math.max(1, n - Math.min(n, avail.getOrDefault(item, 0))));
        return miss;
    }

    /** What's missing to make it this one way: the way's own parts it can't get or make from what it has. */
    private static Map<Item, Integer> missingBy(Level level, Item item, RecipeBook.Way way, int n, Map<Item, Integer> avail) {
        Map<Item, Integer> pool = new LinkedHashMap<>(avail);
        Map<Item, Integer> miss = new LinkedHashMap<>();
        int crafts = (n + way.yield() - 1) / way.yield();
        for (RecipeBook.Part part : way.parts()) {
            int need = part.count() * crafts;
            Item rep = choose(pool, part);
            int have = rep == null ? 0 : pool.getOrDefault(rep, 0);
            int use = Math.min(have, need);
            if (rep != null && use > 0) pool.merge(rep, -use, Integer::sum);
            int left = need - use;
            if (left <= 0) continue;
            Item make = rep != null ? rep : first(part);
            if (makePart(level, part, left, pool, new HashSet<>(List.of(item)), 1)) continue;
            miss.merge(make == null ? Items.BARRIER : make, left, Integer::sum);
        }
        return miss;
    }

    /** Can it make {@code n} of this from the pool (taking what it uses)? Every way the game has: benches and fires. */
    private static boolean makeable(Level level, Item item, int n, Map<Item, Integer> pool, Set<Item> path, int depth) {
        if (n <= 0) return true;
        if (take(pool, item, n)) return true;
        if (depth > 8 || path.contains(item)) return false;
        Map<Item, Integer> start = new LinkedHashMap<>(pool);
        int have = pool.getOrDefault(item, 0);
        if (have > 0) { pool.put(item, 0); n -= have; }
        path.add(item);
        try {
            for (RecipeBook.Way w : RecipeBook.waysFor(level, item)) {
                Map<Item, Integer> trial = new LinkedHashMap<>(pool);
                int crafts = (n + w.yield() - 1) / w.yield();
                boolean ok = true;
                for (RecipeBook.Part part : w.parts()) {
                    if (!makePart(level, part, part.count() * crafts, trial, path, depth + 1)) { ok = false; break; }
                }
                if (ok) { pool.clear(); pool.putAll(trial); return true; }
            }
            pool.clear();
            pool.putAll(start);
            return false;
        } finally {
            path.remove(item);
        }
    }

    /**
     * Can it come by {@code need} of a recipe part (any of the items it accepts: "any planks" is oak or birch or...)?
     * What it has first, then each kind it could make from the pool, tried on a copy so a dead end costs nothing.
     */
    private static boolean makePart(Level level, RecipeBook.Part part, int need, Map<Item, Integer> pool, Set<Item> path, int depth) {
        Item rep = choose(pool, part);
        if (rep != null) {
            Map<Item, Integer> trial = new LinkedHashMap<>(pool);
            if (makeable(level, rep, need, trial, path, depth)) { pool.clear(); pool.putAll(trial); return true; }
        }
        ItemStack[] cands = part.ingredient().getItems();
        for (int i = 0; i < cands.length && i < 48; i++) {
            Item c = cands[i].getItem();
            if (c == rep) continue;
            Map<Item, Integer> trial = new LinkedHashMap<>(pool);
            if (makeable(level, c, need, trial, path, depth)) { pool.clear(); pool.putAll(trial); return true; }
        }
        return false;
    }

    /** What a furnace makes this from, the kinds it has first (raw iron before iron ore). */
    static List<Item> smeltFroms(Level level, Item out, Map<Item, Integer> pool) {
        List<Item> have = new ArrayList<>(), rest = new ArrayList<>();
        for (RecipeHolder<net.minecraft.world.item.crafting.SmeltingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
            if (!h.value().getResultItem(level.registryAccess()).is(out) || h.value().getIngredients().isEmpty()) continue;
            for (ItemStack in : h.value().getIngredients().get(0).getItems()) {
                Item it = in.getItem();
                if (have.contains(it) || rest.contains(it)) continue;
                if (pool.getOrDefault(it, 0) > 0) have.add(it); else rest.add(it);
            }
        }
        have.addAll(rest.subList(0, Math.min(4, rest.size())));
        return have;
    }

    private static boolean take(Map<Item, Integer> pool, Item item, int n) {
        int have = pool.getOrDefault(item, 0);
        if (have >= n) { pool.put(item, have - n); return true; }
        return false;
    }

    /** The item matching a recipe part that it has most of (so "any planks" takes the birch it has), or null. */
    @Nullable
    private static Item choose(Map<Item, Integer> pool, RecipeBook.Part part) {
        Item best = null;
        int most = 0;
        for (Map.Entry<Item, Integer> e : pool.entrySet()) {
            if (e.getValue() > most && part.ingredient().test(new ItemStack(e.getKey()))) { most = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    @Nullable
    private static Item first(RecipeBook.Part part) {
        ItemStack[] items = part.ingredient().getItems();
        return items.length == 0 ? null : items[0].getItem();
    }

    /** What a furnace makes this from (iron ingot <- raw iron), or null. */
    @Nullable
    static Item smeltFrom(Level level, Item out) {
        for (RecipeHolder<net.minecraft.world.item.crafting.SmeltingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
            if (h.value().getResultItem(level.registryAccess()).is(out)) {
                ItemStack[] in = h.value().getIngredients().isEmpty() ? new ItemStack[0] : h.value().getIngredients().get(0).getItems();
                if (in.length > 0) return in[0].getItem();
            }
        }
        return null;
    }

    static String list(Map<Item, Integer> m) {
        List<String> bits = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : m.entrySet()) bits.add(e.getValue() + " " + PaLogiItems.nameOf(e.getKey(), e.getValue()));
        return PaLogiPlan.join(bits);
    }

    // ------------------------------------------------------------------ the CRAFT order (PaOrders hook)

    /**
     * Before the engine plans a craft: missing parts are said exactly, with choices; rare ingredients asked about; the
     * result handed over or put on afterwards. Null: carry on with the usual plan. "": answered, nothing to plan.
     */
    @Nullable
    public static String check(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaIntent i, List<PaPlan.Step> out) {
        if (i.raw() != null && i.raw().startsWith("#go ")) return null;                           // "go and get them": the engine's full chain
        if (!a.plan.idle() || !PaLogiPlan.alone(i.raw())) return null;                           // a later step: what it needs may come from the steps before
        Item item = itemFor(a.level(), i.item());
        if (item == null || item == Items.AIR) return null;
        int n = Math.max(1, i.amount());
        Map<Item, Integer> avail = available(a, true);
        Map<Item, Integer> miss = missing(a.level(), item, n, avail);
        if (!miss.isEmpty()) {
            String need = list(miss);
            List<Choice> ch = new ArrayList<>();
            boolean hers = false;
            if (p != null) {
                Map<Item, Integer> pa = new LinkedHashMap<>();
                for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) pa.merge(s.getItem(), s.getCount(), Integer::sum);
                hers = miss.entrySet().stream().allMatch(e -> pa.getOrDefault(e.getKey(), 0) >= e.getValue());
            }
            if (hers) ch.add(new Choice("Hand over", "#logi crafthand"));
            ch.add(new Choice("Go and get them", "#logi craftgo"));
            ch.add(new Choice("Leave it", "#logi craftno"));
            if (p != null) PENDING.put(p.getUUID(), new Pending(BuiltInRegistries.ITEM.getKey(item).toString(), n, miss, i.raw() == null ? "" : i.raw(), false));
            PaTalk.reply(a, PaLogiTalk.line(a, "craft.short", "need", need), ch);
            return "";
        }
        return null;
    }

    // ---- a set of armour: "make me a full set of iron armour"

    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};
    private static final int[] PIECE_COST = {5, 8, 7, 4};

    /** The four pieces when the thing asked for is a whole set of armour (its material picked from what it has when unsaid); else null. */
    @Nullable
    public static List<PaIntent> pieces(PlayerAssistantEntity a, PaIntent i) {
        String it = i.item() == null ? "" : i.item().toLowerCase(Locale.ROOT).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:(leather|iron|golden|gold|diamond|netherite|chainmail) )?armou?r(?: set| suit)?$").matcher(it);
        if (!m.matches()) return null;
        String mat = m.group(1) == null ? "" : m.group(1).equals("gold") ? "golden" : m.group(1);
        if (mat.isEmpty()) {
            Map<Item, Integer> av = available(a, true);
            mat = av.getOrDefault(Items.DIAMOND, 0) >= 24 ? "diamond" : av.getOrDefault(Items.IRON_INGOT, 0) >= 24 ? "iron"
                : av.getOrDefault(Items.LEATHER, 0) >= 24 ? "leather" : "iron";
        }
        List<PaIntent> out = new ArrayList<>();
        for (String piece : PIECES) out.add(new PaIntent(PaIntent.Kind.CRAFT, mat + " " + piece, 1, PaIntent.Place.NONE, ""));
        return out;
    }

    /** What the set needs in all, short of what it has (pack and chests), checked before any piece is begun; null when it has enough. */
    @Nullable
    public static String setShort(PlayerAssistantEntity a, PaIntent i, List<PaIntent> set) {
        if (!a.plan.idle() || !PaLogiPlan.alone(i.raw()) || (i.raw() != null && i.raw().startsWith("#go "))) return null;
        String mat = set.get(0).item().split(" ")[0];
        Item stuff = switch (mat) {
            case "iron" -> Items.IRON_INGOT;
            case "golden" -> Items.GOLD_INGOT;
            case "diamond" -> Items.DIAMOND;
            case "leather" -> Items.LEATHER;
            default -> null;
        };
        if (stuff == null) return null;                                    // netherite / chainmail: the usual planner says what it can't do
        Map<Item, Integer> av = available(a, true);
        int want = 0;
        for (int k = 0; k < PIECES.length; k++) {
            Item made = itemFor(a.level(), mat + " " + PIECES[k]);
            if (made == null || av.getOrDefault(made, 0) == 0) want += PIECE_COST[k];
        }
        int have = av.getOrDefault(stuff, 0);
        if (stuff == Items.IRON_INGOT) have += av.getOrDefault(Items.RAW_IRON, 0);
        if (stuff == Items.GOLD_INGOT) have += av.getOrDefault(Items.RAW_GOLD, 0);
        if (have >= want) return null;
        Map<Item, Integer> miss = new LinkedHashMap<>();
        miss.put(stuff, want - have);
        String raw = i.raw() == null ? "" : i.raw();
        PaTalk.reply(a, PaLogiTalk.line(a, "craft.short", "need", list(miss)), List.of(new Choice("Go and get them", "#go " + raw), new Choice("Leave it", "no")));
        return "";
    }

    /** After the pieces' own steps: the set handed over in one go, or put on. */
    public static void deliverSet(PlayerAssistantEntity a, PaIntent i, List<PaPlan.Step> out, List<PaIntent> set) {
        String raw = i.raw() == null ? "" : i.raw().toLowerCase(Locale.ROOT);
        if (raw.matches(".*\\b(?:make|craft|build|forge) me\\b.*|.*\\bfor me\\b.*|.*\\bgive (?:it|them) to me\\b.*")) {
            PaPlan.Step give = PaOrders.step("bring you the " + set.get(0).item().split(" ")[0] + " armour", PaIntent.Kind.GIVE, 20);
            for (PaIntent piece : set) give.jobs.add(Job.give(piece.item(), 1));
            out.add(give);
        } else if (raw.matches(".*\\b(?:make|craft|forge) yourself\\b.*|.*\\bfor yourself\\b.*")) {
            PaPlan.Step eq = PaOrders.step("put it on", PaIntent.Kind.CRAFT, 5);
            eq.task = new PaTask(PaTask.Kind.EQUIP, null, "", 0);
            out.add(eq);
        }
    }

    /** After the usual plan: "make me ..." hands it over, "make yourself ..." puts it on. */
    public static void deliver(PlayerAssistantEntity a, PaIntent i, List<PaPlan.Step> out, String what, int n) {
        String raw = i.raw() == null ? "" : i.raw().toLowerCase(Locale.ROOT);
        if (raw.matches(".*\\b(?:make|craft|build|forge|cook|bake) me\\b.*|.*\\bfor me\\b.*|.*\\bgive (?:it|them) to me\\b.*")) {
            PaPlan.Step give = PaOrders.step("bring you " + (n > 1 ? n + " " : "") + what, PaIntent.Kind.GIVE, 15);
            give.jobs.add(Job.give(what, n));
            out.add(give);
        } else if (raw.matches(".*\\b(?:make|craft|forge) yourself\\b.*|.*\\bfor yourself\\b.*")) {
            PaPlan.Step eq = PaOrders.step("put it on", PaIntent.Kind.CRAFT, 5);
            eq.task = new PaTask(PaTask.Kind.EQUIP, null, "", 0);
            out.add(eq);
        }
    }

    /** The choices on a shortfall: hand them over (from its player's inventory), go and get them, or leave it. */
    public static void choice(ServerPlayer p, PlayerAssistantEntity a, String what) {
        Pending pd = PENDING.remove(p.getUUID());
        if (pd == null) { PaTalk.reply(a, "There's nothing waiting to be made."); return; }
        switch (what) {
            case "crafthand" -> {
                if (a.distanceToSqr(p) > 8 * 8) { PENDING.put(p.getUUID(), pd); PaTalk.reply(a, PaLogiTalk.line(a, "logi.handover_far"), List.of(new Choice("Hand over", "#logi crafthand"))); return; }
                var inv = p.getInventory();
                for (Map.Entry<Item, Integer> e : pd.missing().entrySet()) {
                    int left = e.getValue();
                    for (int k = 0; k < inv.items.size() && left > 0; k++) {
                        ItemStack s = inv.items.get(k);
                        if (!s.is(e.getKey())) continue;
                        int take = Math.min(left, s.getCount());
                        ItemStack rest = a.insertItem(s.copyWithCount(take));
                        int moved = take - rest.getCount();
                        s.shrink(moved);
                        left -= moved;
                        if (moved < take) break;
                    }
                }
                inv.setChanged();
                a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                PaTalk.reply(a, "Ta. Making it now.");
                runCraft(p, a, pd.item(), pd.n(), pd.said(), false);
            }
            case "craftgo" -> runCraft(p, a, pd.item(), pd.n(), pd.said(), true);
            default -> PaTalk.reply(a, "Right. Leaving it.");
        }
    }

    /** Plan and start a craft (going for the parts when {@code go}), with its delivery. */
    static void runCraft(ServerPlayer p, PlayerAssistantEntity a, String itemId, int n, String said, boolean go) {
        Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(itemId));
        String words = RecipeBook.words(item);
        String raw = (go ? "#go " : "") + (said == null || said.isEmpty() ? "craft " + n + " " + words : said);
        PaOrders.run(p, a, List.of(new PaIntent(PaIntent.Kind.CRAFT, words, n, PaIntent.Place.NONE, raw)), PaOrders.Look.NONE, said == null || said.isEmpty() ? "craft " + words : said);
    }

    // ------------------------------------------------------------------ questions and the Craft view

    /** "How do I make a piston?" / "what do you need for a beacon?": the recipe, with what it has ticked. */
    public static void recipe(@Nullable ServerPlayer p, PlayerAssistantEntity a, String phrase, boolean view) {
        Item item = itemFor(a.level(), phrase);
        if (item == null || item == Items.AIR) { PaTalk.reply(a, "I don't know a recipe for " + phrase + "."); return; }
        RecipeBook.Way craft = null;                                             // the usual bench recipe (sticks of planks, not bamboo)
        for (RecipeBook.Way w : RecipeBook.waysFor(a.level(), item)) if (w.fire() == RecipeBook.Fire.NONE) { craft = w; break; }
        String name = PaLogiItems.nameOf(item, 1);
        if (craft == null) {
            Item raw = smeltFrom(a.level(), item);
            PaTalk.reply(a, raw != null ? "A " + name + " comes out of a furnace: smelt " + PaLogiItems.nameOf(raw, 1) + "." : "There's no crafting recipe for " + name + ". It's found, not made.");
            return;
        }
        Map<Item, Integer> avail = available(a, true);
        List<String> bits = new ArrayList<>();
        CompoundTag t = new CompoundTag();
        t.putString("op", "recipe");
        t.putString("i", BuiltInRegistries.ITEM.getKey(item).toString());
        t.putBoolean("table", craft.needsTable());
        t.putInt("yield", craft.yield());
        ListTag parts = new ListTag();
        for (RecipeBook.Part part : craft.parts()) {
            Item rep = choose(avail, part);
            int have = rep == null ? 0 : Math.min(part.count(), avail.getOrDefault(rep, 0));
            Item show = rep != null ? rep : first(part);
            String label = part.label() != null && !part.label().isEmpty() ? part.label() : PaLogiItems.nameOf(show, part.count());
            bits.add(part.count() + " " + label + (have >= part.count() ? " (got)" : have > 0 ? " (have " + have + ")" : " (need)"));
            CompoundTag c = new CompoundTag();
            c.putString("l", label);
            c.putString("i", show == null ? "minecraft:barrier" : BuiltInRegistries.ITEM.getKey(show).toString());
            c.putInt("need", part.count());
            c.putInt("have", have);
            parts.add(c);
        }
        t.put("parts", parts);
        String answer = "A " + name + (craft.yield() > 1 ? " (makes " + craft.yield() + ")" : "") + ": " + PaLogiPlan.join(bits) + "." + (craft.needsTable() ? " At a crafting table." : " Done in the hand.");
        PaTalk.reply(a, PaLogiTalk.line(a, "craft.recipe", "answer", answer));
        if (view && p != null) PaNet.send(p, PaLogiNet.KIND, t);
    }

    /** "What can you make with this?": things it could make now from its pack and the item its player holds. */
    public static void canMake(@Nullable ServerPlayer p, PlayerAssistantEntity a) {
        Map<Item, Integer> avail = available(a, false);
        if (p != null && !p.getMainHandItem().isEmpty()) avail.merge(p.getMainHandItem().getItem(), p.getMainHandItem().getCount(), Integer::sum);
        List<String> can = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        Item held = p == null || p.getMainHandItem().isEmpty() ? null : p.getMainHandItem().getItem();
        for (RecipeHolder<CraftingRecipe> h : a.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (h.value().isSpecial()) continue;
            ItemStack out = h.value().getResultItem(a.level().registryAccess());
            if (out.isEmpty() || !seen.add(out.getItem())) continue;
            if (held != null && h.value().getIngredients().stream().noneMatch(ing -> ing.test(new ItemStack(held)))) continue;
            if (missing(a.level(), out.getItem(), 1, avail).isEmpty()) can.add(PaLogiItems.nameOf(out.getItem(), 1));
            if (can.size() >= 12) break;
        }
        if (can.isEmpty()) { PaTalk.reply(a, held != null ? "Nothing yet from that and what I've got." : "Nothing, from what I've got on me."); return; }
        PaTalk.reply(a, (held != null ? "With that I could make " : "I could make ") + PaLogiPlan.join(can) + ".");
    }

    /** The Craft view's search: craftable items matching the words, each with "can make now" or what's missing. */
    public static void query(ServerPlayer p, PlayerAssistantEntity a, String q) {
        String w = q == null ? "" : q.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        Map<Item, Integer> avail = available(a, true);
        ListTag l = new ListTag();
        Set<Item> seen = new HashSet<>();
        List<RecipeHolder<CraftingRecipe>> all = new ArrayList<>(a.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING));
        all.sort(java.util.Comparator.comparing(h -> BuiltInRegistries.ITEM.getKey(h.value().getResultItem(a.level().registryAccess()).getItem()).getPath()));
        for (RecipeHolder<CraftingRecipe> h : all) {
            if (h.value().isSpecial()) continue;
            ItemStack out = h.value().getResultItem(a.level().registryAccess());
            if (out.isEmpty() || !seen.add(out.getItem())) continue;
            String path = BuiltInRegistries.ITEM.getKey(out.getItem()).getPath();
            if (!w.isEmpty() && !path.contains(w)) continue;
            Map<Item, Integer> miss = missing(a.level(), out.getItem(), 1, avail);
            if (w.isEmpty() && !miss.isEmpty()) continue;                       // nothing typed: only what it can make now
            CompoundTag c = new CompoundTag();
            c.putString("i", BuiltInRegistries.ITEM.getKey(out.getItem()).toString());
            c.putBoolean("now", miss.isEmpty());
            if (!miss.isEmpty()) c.putString("miss", list(miss));
            c.putInt("max", miss.isEmpty() ? maxMakeable(a.level(), out.getItem(), avail) : 0);
            l.add(c);
            if (l.size() >= 40) break;
        }
        CompoundTag t = new CompoundTag();
        t.putString("op", "craft");
        t.putString("q", q == null ? "" : q);
        t.put("list", l);
        PaNet.send(p, PaLogiNet.KIND, t);
    }

    /** How many it could make now (counting only the recipe's own parts at hand, and up to a few stacks). */
    static int maxMakeable(Level level, Item item, Map<Item, Integer> avail) {
        int lo = 1, hi = 256;
        if (!missing(level, item, 1, avail).isEmpty()) return 0;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (missing(level, item, mid, avail).isEmpty()) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Craft ×1, ×N or Craft all, from the Craft view. */
    public static void craftFromView(ServerPlayer p, PlayerAssistantEntity a, String itemId, int n, boolean all) {
        Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(itemId));
        if (item == Items.AIR) return;
        if (all) n = Math.max(1, maxMakeable(a.level(), item, available(a, true)));
        PaData.Rec r = a.rec();
        String words = RecipeBook.words(item);
        if (r != null) { r.say(0, "Craft " + n + " " + words, a.level().getGameTime()); PaData.touch(); }
        PaOrders.run(p, a, List.of(new PaIntent(PaIntent.Kind.CRAFT, words, n, PaIntent.Place.NONE, "craft me " + n + " " + words)), PaOrders.Look.NONE, "craft me " + n + " " + words);
    }
}
