package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Op;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Src;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * [pa36] One logistics job under way: the order, the containers it resolved to, how far it has got, and the steps of
 * doing it as a player would. Walk to the chest (round doors and up stairs: the engine's own path-finding), stand beside
 * it, lift the lid, move a stack per hand action (PaLogistics.act: a player's pace), close the lid, and go on: to the
 * next chest when one is full, back out for more when filling, to its player to hand things over.
 *
 * <p>Gathering, crafting, smelting with no furnace of its own, hunting and picking up dropped things are handed to the
 * original engine as jobs; the job waits (its goal stands aside) and counts what came back. "Stop" ends it at once and
 * it says what it moved.
 */
public final class PaLogiJob {

    final PaLogiOrder o;
    final List<BlockPos> to = new ArrayList<>(), from = new ArrayList<>();
    /** Items to move (-1: as many as there are). FUEL/SORT: containers. */
    int target = -1;
    int moved, trips, tripsWanted = -1, stockHave, given;
    @Nullable PaStore.Stock rule;
    @Nullable BlockPos area;
    boolean fetchFuel, quietStart, ownWords, finished;

    // ---- the run
    private String phase = "start";
    @Nullable private Run run;
    private int lastHave, dry, waitUntil, rounds, hunts, stopIdx, sortWait, smeltIn, smeltOut;
    private long smeltDeadline;
    private boolean shortfall, saidWait, furtherTried;
    private final Map<String, Integer> before = new HashMap<>();
    private final List<Stop> stops = new ArrayList<>();
    private final Set<String> outputs = new LinkedHashSet<>();

    private PaLogiJob(PaLogiOrder o) {
        this.o = o;
    }

    static PaLogiJob of(PaLogiOrder o) {
        return new PaLogiJob(o);
    }

    // ------------------------------------------------------------------ saved with its plan step

    String save() {
        return o.spec() + "§" + longs(to) + "§" + longs(from) + "§" + target + "§" + (area == null ? "" : area.asLong()) + "§" + tripsWanted + "§" + (rule == null ? "" : rule.pos + "~" + rule.item);
    }

    @Nullable
    static PaLogiJob load(String s) {
        try {
            String[] p = s.split("§", -1);
            PaLogiOrder o = PaLogiOrder.parse(p[0]);
            if (o == null) return null;
            PaLogiJob j = new PaLogiJob(o);
            for (String l : p[1].split(",")) if (!l.isEmpty()) j.to.add(BlockPos.of(Long.parseLong(l)));
            for (String l : p[2].split(",")) if (!l.isEmpty()) j.from.add(BlockPos.of(Long.parseLong(l)));
            j.target = Integer.parseInt(p[3]);
            if (p.length > 4 && !p[4].isEmpty()) j.area = BlockPos.of(Long.parseLong(p[4]));
            if (p.length > 5 && !p[5].isEmpty()) j.tripsWanted = Integer.parseInt(p[5]);
            return j;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String longs(List<BlockPos> l) {
        StringBuilder b = new StringBuilder();
        for (BlockPos p : l) { if (b.length() > 0) b.append(','); b.append(p.asLong()); }
        return b.toString();
    }

    @Nullable
    BlockPos primary() {
        return !to.isEmpty() ? to.get(0) : !from.isEmpty() ? from.get(0) : area;
    }

    // ------------------------------------------------------------------ progress

    String tally(PlayerAssistantEntity a) {
        StringBuilder b = new StringBuilder();
        switch (o.op()) {
            case HAUL -> b.append("trip ").append(trips + 1).append(" · ").append(moved).append(" moved");
            case SORT, FUEL -> { if (target > 0) b.append(Math.min(moved, target)).append('/').append(target); }
            case SMELT -> { if (smeltIn > 0) b.append(smeltOut).append('/').append(smeltIn); }
            default -> {
                if (target > 0) b.append(Math.min(moved, target)).append('/').append(target);
                else if (moved > 0) b.append(moved).append(" moved");
                if (o.op() == Op.FILL && trips > 0) b.append(" · trip ").append(trips);
            }
        }
        if (o.op() == Op.FILL && !to.isEmpty() && a.level().isLoaded(to.get(0))) {
            Container c = PaContainers.open(a, to.get(0));
            if (c != null && c.getContainerSize() > 0) b.append(" · chest ").append(100 * (c.getContainerSize() - PaContainers.freeSlots(c)) / c.getContainerSize()).append('%');
        }
        return b.toString();
    }

    int percent() {
        if (o.op() == Op.SMELT) return smeltIn > 0 ? Math.min(100, 100 * smeltOut / smeltIn) : -1;
        if (target > 0) return Math.min(100, moved * 100 / target);
        return -1;
    }

    // ------------------------------------------------------------------ the goal's calls

    void resumed(PlayerAssistantEntity a) {
        if (finished) {
            // started again by the plan (a new pickaxe made, another try): carry on from the count so far
            finished = false;
            ownWords = false;
            run = null;
            phase = o.op() == Op.FILL ? "acquire" : "start";
            dry = 0;
        }
    }

    void paused(PlayerAssistantEntity a) {
        if (run != null) run.close(a);
    }

    void abandon(PlayerAssistantEntity a) {
        if (run != null) run.close(a);
        finished = true;
    }

    private void finish(PlayerAssistantEntity a, String result, boolean failed, boolean own, @Nullable List<Choice> choices) {
        if (run != null) run.close(a);
        PaTask t = a.task;
        finished = true;
        if (t != null) {
            t.progress = 100;
            t.failed = failed;
            t.result = own ? "" : result;
        }
        ownWords = own && !failed;
        PaPlan.Step cur = a.plan.current;
        if (!failed && cur != null) cur.fail = "";                         // whatever the engine muttered on the way, it's done
        if (own && !failed && result != null && !result.isEmpty()) {
            if (rule != null) PaTalk.comment(a, 1, result, true);
            else PaTalk.reply(a, result, choices);
        }
        a.task = null;
        if (a.getOwnerPlayer() instanceof ServerPlayer sp) PaNet.refresh(sp, a);
    }

    private void done(PlayerAssistantEntity a, String line) {
        finish(a, line, false, true, null);
    }

    private void fail(PlayerAssistantEntity a, String why) {
        finish(a, why, true, false, null);
    }

    // ------------------------------------------------------------------ the work

    void tick(PlayerAssistantEntity a) {
        if (finished) return;
        if (waitUntil > a.tickCount) {
            if (run != null && run.at != null) a.getLookControl().setLookAt(Vec3.atCenterOf(run.at));
            return;
        }
        switch (o.op()) {
            case DEPOSIT -> deposit(a);
            case EMPTY_PACK -> emptyPack(a);
            case FILL -> fill(a);
            case FETCH, TAKE -> fetch(a);
            case MOVE -> move(a);
            case SORT -> sort(a);
            case FUEL -> fuel(a);
            case SMELT -> smelt(a);
            case COLLECT -> collect(a);
            case COMPOST -> compost(a);
            case HAUL -> haul(a);
            case DEATH -> death(a);
            default -> fail(a, "I'm not sure how to do that yet.");
        }
    }

    /** What the order's item means here: "*" is everything but its own gear and food. */
    static Predicate<ItemStack> what(PlayerAssistantEntity a, String item) {
        if (item == null || item.isEmpty() || item.equals("*") || item.equals("it"))
            return s -> !s.isEmpty() && !PaLogiItems.gear(a, s) && s.get(DataComponents.FOOD) == null;
        return PaLogiItems.of(item);
    }

    private Predicate<ItemStack> pred(PlayerAssistantEntity a) {
        return what(a, o.item());
    }

    private static int count(PlayerAssistantEntity a, Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && p.test(s)) n += s.getCount();
        return n;
    }

    private String thing(int n) {
        return PaLogiItems.name(o.item(), n);
    }

    private String chestName(PlayerAssistantEntity a) {
        BlockPos b = PaLogistics.LAST.get(a);
        if (b == null && !to.isEmpty()) b = to.get(0);
        return b == null ? "the chest" : PaLogistics.called(a, b, o.to());
    }

    // ---- DEPOSIT: its pack into a container

    private void deposit(PlayerAssistantEntity a) {
        if (phase.equals("start")) {
            run = new Run(to, this);
            phase = "put";
        }
        int most = target < 0 ? -1 : target - moved;
        int r = run.unload(a, pred(a), most);
        if (r == 0) return;
        if (r < 0) {
            fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a)));
            return;
        }
        if (moved == 0) { done(a, "Nothing of that sort in my pack to put away."); return; }
        done(a, PaLogiTalk.line(a, "logi.deposit_done", "n", String.valueOf(moved), "item", thing(moved), "chest", chestName(a)));
    }

    // ---- EMPTY_PACK: each kind where its kind already lives, the rest in storage

    private record Stop(BlockPos pos, Set<String> ids) {}

    private void emptyPack(PlayerAssistantEntity a) {
        if (phase.equals("start")) {
            planStops(a);
            stopIdx = 0;
            run = null;
            phase = "stops";
        }
        if (stopIdx >= stops.size()) {
            // anything left (a chest filled up on the way): the storage and its overflow
            Predicate<ItemStack> rest = pred(a);
            if (count(a, rest) > 0 && !phase.equals("rest")) { phase = "rest"; run = new Run(to, this); }
            if (phase.equals("rest")) {
                int r = run.unload(a, rest, -1);
                if (r == 0) return;
                if (r < 0) { fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a))); return; }
            }
            Set<BlockPos> used = new LinkedHashSet<>();
            for (Stop s : stops) used.add(s.pos);
            if (moved == 0) { done(a, "My pack had nothing to put away but my own kit."); return; }
            done(a, PaLogiTalk.line(a, "logi.empty_done", "n", String.valueOf(moved), "chests", String.valueOf(Math.max(1, used.size()))));
            return;
        }
        Stop s = stops.get(stopIdx);
        if (run == null) run = new Run(new ArrayList<>(List.of(s.pos)), this);
        run.noOverflow = true;
        Predicate<ItemStack> base = pred(a);
        int r = run.unload(a, st -> base.test(st) && s.ids.contains(PaLogiItems.id(st)), -1);
        if (r == 0) return;
        run.close(a);
        run = null;
        stopIdx++;
    }

    /** Which container each kind in its pack goes to: one that already holds that kind (and has room), else storage. Nearest-first route. */
    private void planStops(PlayerAssistantEntity a) {
        stops.clear();
        Map<String, ItemStack> kinds = new java.util.LinkedHashMap<>();
        Predicate<ItemStack> base = pred(a);
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && base.test(s)) kinds.putIfAbsent(PaLogiItems.id(s), s);
        java.util.UUID owner = a.getOwnerId();
        String dim = a.level().dimension().location().toString();
        BlockPos centre = to.isEmpty() ? a.blockPosition() : to.get(0);
        List<BlockPos> known = owner == null ? List.of() : PaLogistics.nearest(a, PaStore.of(owner), dim, centre, "chest", 32, true);
        Map<BlockPos, Set<String>> plan = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> e : kinds.entrySet()) {
            BlockPos home = null;
            for (BlockPos b : known) {
                if (PaContainers.refuse(a, b) != null) continue;
                Container c = PaContainers.open(a, b);
                if (c != null && PaContainers.count(c, st -> PaLogiItems.id(st).equals(e.getKey())) > 0 && PaContainers.room(c, e.getValue()) > 0) { home = b; break; }
            }
            if (home == null) home = to.isEmpty() ? null : to.get(0);
            if (home != null) plan.computeIfAbsent(home, k -> new LinkedHashSet<>()).add(e.getKey());
        }
        // nearest-neighbour route from where it stands
        BlockPos at = a.blockPosition();
        List<BlockPos> left = new ArrayList<>(plan.keySet());
        while (!left.isEmpty()) {
            BlockPos here = at;
            BlockPos next = left.stream().min(java.util.Comparator.comparingDouble(b -> b.distSqr(here))).get();
            stops.add(new Stop(next, plan.get(next)));
            left.remove(next);
            at = next;
        }
    }

    // ---- FILL: gather, craft or smelt, and carry it in, as many trips as it takes

    private void fill(PlayerAssistantEntity a) {
        Predicate<ItemStack> p = pred(a);
        Item sample = PaLogiItems.sample(o.item());
        switch (phase) {
            case "start" -> {
                if (!quietStart && rule == null)
                    PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.fill_start", "n", String.valueOf(target), "item", thing(target), "chest", chestName(a)), true);
                phase = "acquire";
            }
            case "acquire" -> {
                int left = target - moved;
                int carried = count(a, p);
                if (left <= 0) { finishFill(a); return; }
                int room = PaContainers.packRoom(a, new ItemStack(sample));
                if (carried >= left || (carried > 0 && (room < Math.min(16, sample.getDefaultMaxStackSize()) || dry > 0))) {
                    phase = "unload";
                    run = new Run(to, this);
                    trips++;
                    if (trips > 1) PaTalk.comment(a, 2, PaLogiTalk.line(a, "logi.trip", "have", String.valueOf(moved), "n", String.valueOf(target), "trip", String.valueOf(trips), "chest", chestName(a)), true);
                    return;
                }
                if (room <= 0) {
                    if (carried > 0) { phase = "unload"; run = new Run(to, this); trips++; return; }
                    fail(a, "My pack's full of other things. Let me empty it first: say \"empty your pack\".");
                    return;
                }
                acquire(a, p, Math.max(1, Math.min(left - carried, room)), carried);
            }
            case "engine" -> afterEngine(a, p, sample);
            case "take" -> {
                int r = run.load(a, p, target - moved - count(a, p));
                if (r == 0) return;
                run.close(a);
                if (count(a, p) == 0) { shortfall = true; finishFill(a); return; }
                phase = "acquire";
                dry = 1;                                                     // what the chests had, then in it goes
            }
            case "unload" -> {
                int left = target - moved;
                int r = run.unload(a, p, left);
                if (r == 0) return;
                if (r < 0) { fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a))); return; }
                run.close(a);
                if (shortfall || (dry >= 2 && count(a, p) == 0)) { finishFill(a); return; }
                phase = "acquire";
            }
            default -> phase = "acquire";
        }
    }

    /** Get {@code n} more of the thing into its pack: the gatherer, the recipe book, a furnace, a hunt, or its player's other chests. */
    private void acquire(PlayerAssistantEntity a, Predicate<ItemStack> p, int n, int carried) {
        String item = o.item();
        GatherGoal.Kind k = o.src() == Src.CRAFT ? null : PaLogiItems.kind(item);
        lastHave = carried;
        if (k != null) {
            String lock = PaLogistics.locked(a, k, thing(2));
            if (lock != null) { finish(a, lock, false, true, PaLogistics.lockedChoices(thing(2))); return; }
            if (k.needsProperTool() && !PaItems.hasPickaxe(a)) {
                PaPlan.Step pick = PaOrders.pickaxeStep(a);
                if (pick == null) { fail(a, "I need a pickaxe for " + k.label + ", and I've nothing to make one with."); return; }
                for (Job j : pick.jobs) a.enqueue(j);
            }
            a.enqueue(Job.gather(k, n));
            phase = "engine";
            return;
        }
        String raw = rawFor(a, item);
        if (raw != null) {
            Predicate<ItemStack> rp = PaLogiItems.of(raw.equals("iron") ? "raw iron" : raw.equals("gold") ? "raw gold" : raw.equals("copper") ? "raw copper" : raw);
            int rawHave = count(a, rp);
            if (rawHave > 0) {
                a.enqueue(Job.smelt(raw, Math.min(n, rawHave)));
                phase = "engine";
                return;
            }
            GatherGoal.Kind rk = PaLogiItems.kind(raw);
            if (rk != null) {
                String lock = PaLogistics.locked(a, rk, raw);
                if (lock != null) { finish(a, lock, false, true, PaLogistics.lockedChoices(raw)); return; }
                if (rk.needsProperTool() && !PaItems.hasPickaxe(a)) {
                    PaPlan.Step pick = PaOrders.pickaxeStep(a);
                    if (pick != null) for (Job j : pick.jobs) a.enqueue(j);
                }
                a.enqueue(Job.gather(rk, n));
                a.enqueue(Job.smelt(raw, n));
                phase = "engine";
                return;
            }
            if (raw.equals("beef") && hunts < 3) {
                hunts++;
                a.enqueue(Job.hunt(null, Math.max(1, Math.min(4, (n + 1) / 2))));
                phase = "engine";
                return;
            }
        }
        // its player's other chests have some: fetched from there (not the one being filled)
        List<BlockPos> others = new ArrayList<>();
        java.util.UUID owner = a.getOwnerId();
        if (owner != null && rounds++ < 3) {
            String dim = a.level().dimension().location().toString();
            for (BlockPos b : PaLogistics.nearest(a, PaStore.of(owner), dim, a.blockPosition(), "", 64, true)) {
                if (to.contains(b) || PaLogistics.sameChest(a.level(), b, to.get(0)) || PaContainers.refuse(a, b) != null) continue;
                if (PaContainers.furnace(PaContainers.type(a.level(), b))) continue;
                Container c = PaContainers.open(a, b);
                if (c != null && PaContainers.count(c, p) > 0) others.add(b);
            }
        }
        if (!others.isEmpty()) {
            run = new Run(others, this);
            run.noOverflow = true;
            phase = "take";
            return;
        }
        String target2 = CraftPlanner.matchTarget(item.startsWith("item:") ? item.substring(item.indexOf(':', 5) + 1) : item);
        if (target2 != null) {
            CraftPlanner.Result cr = CraftPlanner.plan(a, target2, n);
            if (!cr.blockers().isEmpty()) {
                String need = String.join(", ", cr.blockers());
                if (moved > 0 || carried > 0) { shortfall = true; if (carried > 0) { phase = "unload"; run = new Run(to, this); } else finishFill(a); return; }
                finish(a, PaLogiTalk.line(a, "craft.short", "need", need), false, true,
                    List.of(new Choice("I'll hand them over", "#pack"), new Choice("Cancel", "stop")));
                return;
            }
            for (Job j : cr.jobs()) a.enqueue(j);
            phase = "engine";
            return;
        }
        if (moved > 0 || carried > 0) { shortfall = true; if (carried > 0) { phase = "unload"; run = new Run(to, this); } else finishFill(a); return; }
        fail(a, "I don't know how to get " + thing(2) + ".");
    }

    /** The engine's work came back: count it; nothing came: look further afield once, then say so honestly. */
    private void afterEngine(PlayerAssistantEntity a, Predicate<ItemStack> p, Item sample) {
        int have = count(a, p);
        int gained = have - lastHave;
        PaPlan.Step cur = a.plan.current;
        String engineSaid = cur == null ? "" : cur.fail;
        if (cur != null) cur.fail = "";                                     // the engine's words were for this round only
        if (gained > 0) {
            dry = 0;
            phase = "acquire";
            return;
        }
        if (!engineSaid.isEmpty() && engineSaid.matches("(?i).*(?:without a pickaxe|no pickaxe|need a pickaxe|pickaxe broke).*") && !PaItems.hasPickaxe(a)) {
            fail(a, engineSaid);                                             // the plan makes a new pickaxe and starts this again
            return;
        }
        dry++;
        GatherGoal.Kind k = PaLogiItems.kind(o.item());
        if (dry == 1 && k != null && !furtherTried) {
            furtherTried = true;
            PaData.Rec r = a.rec();
            int roam = r == null ? 32 : r.roam;
            BlockPos found = PaFind.nearest(a, k, Math.min(96, Math.max(48, roam * 2)));
            if (found != null && found.distSqr(a.blockPosition()) > 10 * 10) {
                BlockPos stand = PaBrain.beside(a, found);
                a.mind.roamCentre = found;
                a.enqueue(Job.goTo(stand.getX() + " " + stand.getY() + " " + stand.getZ()));
                a.enqueue(Job.gather(k, Math.max(1, Math.min(target - moved - have, PaContainers.packRoom(a, new ItemStack(sample))))));
                lastHave = have;
                int far = (int) Math.sqrt(a.blockPosition().distSqr(found));
                PaTalk.reply(a, "There's no more " + k.label + " right here, but I can see some about " + far + " blocks " + PaOrders.bearing(a.position(), Vec3.atCenterOf(found)) + ". Heading there.");
                return;
            }
        }
        shortfall = true;
        if (have > 0) { phase = "unload"; run = new Run(to, this); trips++; return; }
        finishFill(a);
    }

    private void finishFill(PlayerAssistantEntity a) {
        if (moved >= target || (!shortfall && moved > 0)) {
            if (rule != null) done(a, PaLogiTalk.line(a, "logi.deposit_done", "n", String.valueOf(moved), "item", thing(moved), "chest", chestName(a)));
            else done(a, PaLogiTalk.line(a, "logi.done", "n", String.valueOf(moved), "item", thing(moved), "chest", chestName(a), "trip", String.valueOf(Math.max(1, trips))));
            return;
        }
        if (moved == 0) {
            GatherGoal.Kind k = PaLogiItems.kind(o.item());
            fail(a, "I couldn't find any " + (k != null ? k.label : thing(2)) + " I can reach round here.");
            return;
        }
        String line = PaLogiTalk.line(a, "logi.short", "have", String.valueOf(moved), "n", String.valueOf(target), "item", thing(target), "left", String.valueOf(target - moved));
        PaLogiNet.lastShort(a, this);
        finish(a, line, false, true, List.of(new Choice("Look further", "#logi further"), new Choice("That'll do", "thanks")));
    }

    /** The raw thing a smelted thing comes from, as the engine's smelting word ("iron", "sand", "logs", "beef"), or null. */
    @Nullable
    static String rawFor(PlayerAssistantEntity a, String item) {
        String w = item.startsWith("item:") ? item.substring(item.indexOf(':', 5) + 1).replace('_', ' ') : item;
        return switch (w) {
            case "iron ingot" -> "iron";
            case "gold ingot" -> "gold";
            case "copper ingot" -> "copper";
            case "glass" -> "sand";
            case "charcoal" -> "logs";
            case "cooked food", "cooked meat" -> rawMeat(a);
            case "cooked beef", "steak" -> "beef";
            case "cooked porkchop" -> "porkchop";
            case "cooked chicken" -> "chicken";
            case "cooked mutton" -> "mutton";
            case "cooked cod", "cooked salmon", "cooked fish" -> "fish";
            case "baked potato" -> "potato";
            default -> null;
        };
    }

    static boolean smeltable(String item) {
        return rawFor(null, item) != null || item.equals("cooked food");
    }

    private static String rawMeat(@Nullable PlayerAssistantEntity a) {
        if (a != null) {
            for (ItemStack s : a.getInventoryItems()) {
                if (s.is(Items.BEEF)) return "beef";
                if (s.is(Items.PORKCHOP)) return "porkchop";
                if (s.is(Items.CHICKEN)) return "chicken";
                if (s.is(Items.MUTTON)) return "mutton";
                if (s.is(Items.COD) || s.is(Items.SALMON)) return "fish";
                if (s.is(Items.POTATO)) return "potato";
            }
        }
        return "beef";
    }

    // ---- FETCH and TAKE: from its player's chests, to its player or its own pack

    private void fetch(PlayerAssistantEntity a) {
        Predicate<ItemStack> p = pred(a);
        switch (phase) {
            case "start" -> {
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) before.merge(PaLogiItems.id(s), s.getCount(), Integer::sum);
                run = new Run(from, this);
                run.noOverflow = true;
                run.countOut = true;
                phase = "load";
            }
            case "load" -> {
                int want = target - moved;
                int r = run.load(a, p, want);
                if (r == 0) return;
                run.close(a);
                if (o.op() == Op.TAKE) {
                    if (moved == 0) { fail(a, "There wasn't any to take after all."); return; }
                    done(a, "Took " + moved + " " + thing(moved) + " from " + (from.isEmpty() ? "the chest" : PaLogistics.called(a, from.get(0), o.from())) + ".");
                    return;
                }
                if (moved == 0) { fail(a, PaLogiTalk.line(a, "logi.none", "item", thing(2))); return; }
                phase = "give";
                run = null;
            }
            case "give" -> {
                ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp ? sp : null;
                if (owner == null || owner.level() != a.level()) { done(a, "You've gone off. I'll keep the " + thing(moved) + " for you."); return; }
                double d = a.distanceToSqr(owner);
                if (d > 2.6 * 2.6) {
                    if (a.tickCount % 15 == 0) a.getNavigation().moveTo(owner, 1.1);
                    return;
                }
                a.getNavigation().stop();
                a.getLookControl().setLookAt(owner, 30, 30);
                int left = moved - given;
                if (left <= 0) {
                    if (moved < target && run != null) { phase = "load"; return; }
                    done(a, PaLogiTalk.line(a, "logi.fetch_done", "n", String.valueOf(given), "item", thing(given)));
                    return;
                }
                if (!PaLogistics.handFree(a)) return;
                PaLogistics.act(a, () -> {
                    int g = handOne(a, owner, p, left);
                    given += g;
                    return g > 0;
                });
                if (count(a, p) == 0 && given < moved) given = moved;          // nothing more of it on it (eaten, dropped): call it given
            }
            default -> phase = "start";
        }
    }

    /** One stack of the thing into its player's inventory (or at their feet when full). */
    static int handOne(PlayerAssistantEntity a, ServerPlayer owner, Predicate<ItemStack> p, int most) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int n = Math.min(most, Math.min(s.getCount(), s.getMaxStackSize()));
            ItemStack give = s.split(n);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            if (!owner.getInventory().add(give) && !give.isEmpty()) owner.drop(give, false);
            owner.containerMenu.broadcastChanges();
            return n;
        }
        return 0;
    }

    // ---- MOVE: from one container to another, a pack-load at a time

    private void move(PlayerAssistantEntity a) {
        Predicate<ItemStack> p = pred(a);
        switch (phase) {
            case "start" -> { run = new Run(from, this); run.noOverflow = true; phase = "load"; lastHave = count(a, p); }
            case "load" -> {
                int already = count(a, p) - lastHave;
                int r = run.load(a, p, target - moved - Math.max(0, already));
                if (r == 0) return;
                run.close(a);
                int got = count(a, p) - lastHave;
                if (got <= 0) { finishMove(a); return; }
                trips++;
                run = new Run(to, this);
                phase = "unload";
                movingNow = got;
            }
            case "unload" -> {
                int r = run.unload(a, p, movingNow);
                if (r == 0) return;
                if (r < 0) { fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a))); return; }
                run.close(a);
                if (moved >= target) { finishMove(a); return; }
                run = new Run(from, this);
                run.noOverflow = true;
                lastHave = count(a, p);
                phase = "load";
            }
            default -> phase = "start";
        }
    }

    private int movingNow;

    private void finishMove(PlayerAssistantEntity a) {
        String f = from.isEmpty() ? "there" : PaLogistics.called(a, from.get(0), o.from()), t2 = to.isEmpty() ? "there" : PaLogistics.called(a, to.get(0), o.to());
        if (moved == 0) { done(a, "There was nothing to move."); return; }
        done(a, PaLogiTalk.line(a, "logi.moved", "n", String.valueOf(moved), "from", f, "to", t2));
    }

    // ---- SORT: like with like, compacted, in order

    private void sort(PlayerAssistantEntity a) {
        if (phase.equals("start")) { stopIdx = 0; phase = "go"; run = null; }
        if (stopIdx >= to.size()) {
            done(a, PaLogiTalk.line(a, "logi.sorted", "n", String.valueOf(moved)));
            return;
        }
        BlockPos b = to.get(stopIdx);
        if (run == null) { run = new Run(new ArrayList<>(List.of(b)), this); sortWait = -1; }
        int v = run.arrive(a);
        if (v == 0) return;
        if (v < 0) { run = null; stopIdx++; return; }
        Container c = PaContainers.open(a, b);
        if (c == null) { run.close(a); run = null; stopIdx++; return; }
        if (sortWait < 0) {
            int stacks = c.getContainerSize() - PaContainers.freeSlots(c);
            sortWait = Math.max(1, stacks);                                   // a hand action per stack, as a player re-stacks a chest
        }
        if (sortWait > 0) {
            if (PaLogistics.act(a, () -> true)) sortWait--;
            return;
        }
        sortContainer(c);
        PaContainers.snapshot(a, b, c);
        moved++;
        run.close(a);
        run = null;
        stopIdx++;
    }

    /** Merge like stacks, order them by kind (tools, armour, food, ores and ingots, blocks, the rest), compact to the front. */
    static void sortContainer(Container c) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            boolean merged = false;
            for (ItemStack m : all) {
                if (ItemStack.isSameItemSameComponents(m, s)) { m.grow(s.getCount()); merged = true; break; }
            }
            if (!merged) all.add(s.copy());
        }
        List<ItemStack> split = new ArrayList<>();
        for (ItemStack m : all) {
            int left = m.getCount();
            while (left > 0) {
                int n = Math.min(left, m.getMaxStackSize());
                split.add(m.copyWithCount(n));
                left -= n;
            }
        }
        split.sort(java.util.Comparator.comparingInt((ItemStack s) -> PaPackRules.group(s.isDamageableItem() && !(s.getItem() instanceof net.minecraft.world.item.ArmorItem),
                s.getItem() instanceof net.minecraft.world.item.ArmorItem, s.get(DataComponents.FOOD) != null, PaLogiItems.id(s),
                s.getItem() instanceof net.minecraft.world.item.BlockItem))
            .thenComparing(PaLogiItems::id).thenComparing(s -> -s.getCount()));
        if (split.size() > c.getContainerSize()) return;                      // can't happen (merging only shrinks), but never lose a thing
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, i < split.size() ? split.get(i) : ItemStack.EMPTY);
        c.setChanged();
    }

    // ---- FUEL: fuel into each furnace

    private void fuel(PlayerAssistantEntity a) {
        if (phase.equals("start")) {
            stopIdx = 0;
            run = null;
            if (count(a, PaLogiItems::fuel) == 0) {
                List<BlockPos> stores = fuelStores(a);
                if (!stores.isEmpty()) { run = new Run(stores, this); run.noOverflow = true; phase = "getfuel"; return; }
                fail(a, PaLogiTalk.line(a, "logi.no_fuel"));
                return;
            }
            phase = "go";
        }
        if (phase.equals("getfuel")) {
            int r = run.load(a, PaLogiItems::fuel, 64);
            if (r == 0) return;
            run.close(a);
            run = null;
            moved = 0;
            if (count(a, PaLogiItems::fuel) == 0) { fail(a, PaLogiTalk.line(a, "logi.no_fuel")); return; }
            phase = "go";
            return;
        }
        if (stopIdx >= to.size()) {
            done(a, PaLogiTalk.line(a, "logi.fuelled", "n", String.valueOf(moved)));
            return;
        }
        BlockPos b = to.get(stopIdx);
        if (run == null) run = new Run(new ArrayList<>(List.of(b)), this);
        int v = run.arrive(a);
        if (v == 0) return;
        if (v < 0) { run = null; stopIdx++; return; }
        Container c = PaContainers.open(a, b);
        if (c == null || c.getContainerSize() < 3) { run.close(a); run = null; stopIdx++; return; }
        ItemStack in = c.getItem(0), fuelNow = c.getItem(1);
        int want = o.qty().mode() == PaQty.Mode.EXACT || o.qty().mode() == PaQty.Mode.STACKS ? o.qty().items(64, 16) : Math.max(8, in.getCount() / 8 + 2);
        if (rule != null) want = Math.max(want, rule.n);
        if (fuelNow.getCount() >= want || count(a, PaLogiItems::fuel) == 0) {
            if (fuelNow.getCount() > 0 || count(a, PaLogiItems::fuel) > 0) moved++;
            run.close(a);
            run = null;
            stopIdx++;
            return;
        }
        if (!PaLogistics.handFree(a)) return;
        int need = want - fuelNow.getCount();
        boolean[] any = {false};
        PaLogistics.act(a, () -> {
            Predicate<ItemStack> f = fuelNow.isEmpty() ? PaLogiItems::fuel : s -> ItemStack.isSameItemSameComponents(s, fuelNow);
            int n = PaContainers.putOne(a, c, f, need, true);
            any[0] = n > 0;
            return n > 0;
        });
        if (!any[0] && PaLogistics.handFree(a)) {
            moved++;
            run.close(a);
            run = null;
            stopIdx++;
        }
    }

    private List<BlockPos> fuelStores(PlayerAssistantEntity a) {
        List<BlockPos> out = new ArrayList<>();
        java.util.UUID owner = a.getOwnerId();
        if (owner == null) return out;
        String dim = a.level().dimension().location().toString();
        for (BlockPos b : PaLogistics.nearest(a, PaStore.of(owner), dim, a.blockPosition(), "chest", 48, true)) {
            if (PaContainers.refuse(a, b) != null) continue;
            Container c = PaContainers.open(a, b);
            if (c != null && PaContainers.count(c, PaLogiItems::fuel) > 0) out.add(b);
        }
        return out;
    }

    // ---- SMELT: inputs in, fuel in, the furnace's own time, the outputs out

    private boolean smeltable(PlayerAssistantEntity a, ItemStack s) {
        if (s.isEmpty()) return false;
        if (PaLogiItems.fuel(s) && !(o.item().contains("log") && s.is(net.minecraft.tags.ItemTags.LOGS))) return false;   // its fuel isn't for smelting
        return !PaLogiItems.smelts(a.level(), s, RecipeType.SMELTING).isEmpty();
    }

    private Predicate<ItemStack> inputs(PlayerAssistantEntity a) {
        String item = o.item();
        Predicate<ItemStack> base = item.equals("*") || item.equals("ore") ? (item.equals("ore") ? PaLogiItems.of("ore") : s -> true) : PaLogiItems.of(item);
        return s -> base.test(s) && smeltable(a, s) && !PaLogiItems.gear(a, s);
    }

    private void smelt(PlayerAssistantEntity a) {
        Predicate<ItemStack> in = inputs(a);
        switch (phase) {
            case "start" -> {
                if (!from.isEmpty()) { run = new Run(from, this); run.noOverflow = true; phase = "takeSrc"; return; }
                phase = "load";
            }
            case "takeSrc" -> {
                int r = run.load(a, in, -1);
                if (r == 0) return;
                run.close(a);
                moved = 0;
                phase = "load";
            }
            case "load" -> {
                int total = count(a, in);
                if (total == 0) { fail(a, from.isEmpty() ? "I've nothing on me that smelts." : "There's nothing in there that smelts."); return; }
                if (target > 0) total = Math.min(total, target);
                if (count(a, PaLogiItems::fuel) == 0) {
                    List<BlockPos> stores = fuelStores(a);
                    if (stores.isEmpty()) { fail(a, PaLogiTalk.line(a, "logi.no_fuel")); return; }
                    run = new Run(stores, this);
                    run.noOverflow = true;
                    phase = "smeltfuel";
                    return;
                }
                smeltIn = total;
                smeltOut = 0;
                stopIdx = 0;
                run = null;
                phase = "feed";
                int per = (int) Math.ceil(total / (double) Math.max(1, to.size()));
                int secs = per * (fastFurnace(a, to.get(0)) ? 5 : 10);
                PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.smelt_start", "n", String.valueOf(total), "item", inName(a, in, total), "secs", String.valueOf(secs)), true);
                smeltDeadline = a.level().getGameTime() + (long) (per * 220 + 600);
            }
            case "smeltfuel" -> {
                int r = run.load(a, PaLogiItems::fuel, 32);
                if (r == 0) return;
                run.close(a);
                moved = 0;
                if (count(a, PaLogiItems::fuel) == 0) { fail(a, PaLogiTalk.line(a, "logi.no_fuel")); return; }
                phase = "load";
            }
            case "feed" -> {
                if (stopIdx >= to.size()) { phase = "wait"; stopIdx = 0; run = null; return; }
                BlockPos b = to.get(stopIdx);
                if (run == null) run = new Run(new ArrayList<>(List.of(b)), this);
                int v = run.arrive(a);
                if (v == 0) return;
                if (v < 0) { run = null; stopIdx++; return; }
                Container c = PaContainers.open(a, b);
                if (c == null || c.getContainerSize() < 3) { run.close(a); run = null; stopIdx++; return; }
                int share = (int) Math.ceil(smeltIn / (double) to.size());
                int placed = c.getItem(0).isEmpty() ? 0 : c.getItem(0).getCount();
                if (!PaLogistics.handFree(a)) return;
                if (placed < share && count(a, in) > 0) {
                    int want = share - placed;
                    PaLogistics.act(a, () -> PaContainers.putOne(a, c, in, want, false) > 0);
                    return;
                }
                int fuelNeed = (int) Math.ceil(c.getItem(0).getCount() / 8.0) + 1;
                ItemStack f = c.getItem(1);
                int fuelHave = f.isEmpty() ? 0 : f.is(net.minecraft.tags.ItemTags.PLANKS) || f.is(net.minecraft.tags.ItemTags.LOGS) ? f.getCount() * 3 / 2 / 8 * 8 / 8 : f.getCount();
                if (fuelHave < fuelNeed && count(a, PaLogiItems::fuel) > 0) {
                    int want = fuelNeed - fuelHave;
                    boolean put = PaLogistics.act(a, () -> PaContainers.putOne(a, c, f.isEmpty() ? PaLogiItems::fuel : s -> ItemStack.isSameItemSameComponents(s, f), want, true) > 0);
                    if (put) return;
                }
                run.close(a);
                run = null;
                stopIdx++;
            }
            case "wait" -> {
                // stand by the furnaces; take the output as it comes, as a player would
                boolean busy = false;
                for (BlockPos b : to) {
                    Container c = PaContainers.open(a, b);
                    if (c == null || c.getContainerSize() < 3) continue;
                    if (!c.getItem(0).isEmpty()) busy = true;
                    if (!c.getItem(2).isEmpty()) {
                        if (run == null || !b.equals(run.target())) run = new Run(new ArrayList<>(List.of(b)), this);
                        int v = run.arrive(a);
                        if (v == 0) return;
                        if (v < 0) { run = null; continue; }
                        if (!PaLogistics.handFree(a)) return;
                        int[] got = {0};
                        PaLogistics.act(a, () -> { got[0] = PaContainers.takeOne(a, c, s -> true, 64, 2, 3); return got[0] > 0; });
                        if (got[0] > 0) {
                            smeltOut += got[0];
                            outputs.add(PaLogiItems.id(a.getInventoryItems().stream().filter(s -> !s.isEmpty()).reduce((x, y) -> y).orElse(ItemStack.EMPTY)));
                        }
                        return;
                    }
                }
                if (run != null) { run.close(a); run = null; }
                if (!to.isEmpty()) {
                    BlockPos first = to.get(0);
                    if (a.distanceToSqr(Vec3.atCenterOf(first)) > 4 * 4 && a.tickCount % 20 == 0) a.walkTo(PaBrain.beside(a, first), 1.0);
                    a.getLookControl().setLookAt(Vec3.atCenterOf(first));
                }
                if (!busy || a.level().getGameTime() > smeltDeadline) {
                    if (!from.isEmpty()) { phase = "back"; run = new Run(from, this); return; }
                    done(a, PaLogiTalk.line(a, "logi.smelt_done", "n", String.valueOf(smeltOut), "item", "")
                        + (smeltOut < smeltIn && busy ? " The rest is still cooking." : ""));
                    return;
                }
                waitUntil = a.tickCount + 20;
            }
            case "back" -> {
                Predicate<ItemStack> outs = s -> outputs.contains(PaLogiItems.id(s));
                int r = run.unload(a, outs, -1);
                if (r == 0) return;
                done(a, "Smelted " + smeltOut + " and put it back in " + PaLogistics.called(a, from.get(0), o.from()) + ".");
            }
            default -> phase = "start";
        }
    }

    private static boolean fastFurnace(PlayerAssistantEntity a, BlockPos b) {
        String t = PaContainers.type(a.level(), b);
        return t.equals("smoker") || t.equals("blast furnace");
    }

    private String inName(PlayerAssistantEntity a, Predicate<ItemStack> in, int n) {
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && in.test(s)) return PaLogiItems.nameOf(s.getItem(), n);
        return thing(n);
    }

    // ---- COLLECT: the furnaces' output

    private void collect(PlayerAssistantEntity a) {
        if (phase.equals("start")) { stopIdx = 0; run = null; phase = "go"; }
        if (stopIdx >= from.size()) {
            done(a, moved == 0 ? "Nothing ready in there yet." : PaLogiTalk.line(a, "logi.collected", "n", String.valueOf(moved)));
            return;
        }
        BlockPos b = from.get(stopIdx);
        if (run == null) run = new Run(new ArrayList<>(List.of(b)), this);
        int v = run.arrive(a);
        if (v == 0) return;
        if (v < 0) { run = null; stopIdx++; return; }
        Container c = PaContainers.open(a, b);
        if (c == null || c.getContainerSize() < 3 || c.getItem(2).isEmpty()) { if (run != null) run.close(a); run = null; stopIdx++; return; }
        if (!PaLogistics.handFree(a)) return;
        int[] got = {0};
        PaLogistics.act(a, () -> { got[0] = PaContainers.takeOne(a, c, s -> true, 64, 2, 3); return got[0] > 0; });
        if (got[0] > 0) moved += got[0];
        else if (PaLogistics.handFree(a)) { fail(a, "My pack's full."); }
    }

    // ---- COMPOST: a handful at a time, as a player clicks

    private void compost(PlayerAssistantEntity a) {
        Predicate<ItemStack> p = PaLogiItems.of(o.item().equals("*") ? "compostables" : o.item());
        Predicate<ItemStack> ok = s -> p.test(s) && ComposterBlock.COMPOSTABLES.containsKey(s.getItem()) && !PaLogiItems.gear(a, s);
        if (phase.equals("start")) { run = new Run(to, this); phase = "go"; }
        int v = run.arrive(a);
        if (v == 0) return;
        if (v < 0) { fail(a, "I couldn't reach the composter."); return; }
        BlockPos b = to.get(0);
        BlockState st = a.level().getBlockState(b);
        if (!(st.getBlock() instanceof ComposterBlock)) { fail(a, "That composter's gone."); return; }
        int level = st.getValue(ComposterBlock.LEVEL);
        if (level >= 8) {
            if (!PaLogistics.handFree(a)) return;
            PaLogistics.act(a, () -> {
                a.level().setBlock(b, st.setValue(ComposterBlock.LEVEL, 0), 3);
                ItemStack meal = new ItemStack(Items.BONE_MEAL);
                ItemStack left = a.insertItem(meal);
                if (!left.isEmpty()) a.spawnAtLocation(left);
                a.level().playSound(null, b, net.minecraft.sounds.SoundEvents.COMPOSTER_EMPTY, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
                return true;
            });
            given++;
            if (o.qty().mode() != PaQty.Mode.EXACT || moved >= target) { done(a, PaLogiTalk.line(a, "logi.composted", "n", String.valueOf(moved)) + " And a bone meal."); }
            return;
        }
        if (level == 7) { waitUntil = a.tickCount + 20; return; }        // it settles into bone meal on its own
        boolean have = count(a, ok) > 0;
        if (!have || (target > 0 && moved >= target)) {
            done(a, moved == 0 ? "I've nothing to compost." : PaLogiTalk.line(a, "logi.composted", "n", String.valueOf(moved)));
            return;
        }
        if (!PaLogistics.handFree(a)) return;
        PaLogistics.act(a, () -> {
            var inv = a.getInventoryItems();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !ok.test(s)) continue;
                BlockState now = a.level().getBlockState(b);
                ComposterBlock.insertItem(a, now, (ServerLevel) a.level(), s, b);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                moved++;
                return true;
            }
            return false;
        });
    }

    // ---- HAUL: back and forth until told to stop

    private void haul(PlayerAssistantEntity a) {
        Predicate<ItemStack> p = pred(a);
        switch (phase) {
            case "start" -> {
                PaTalk.reply(a, PaLogiTalk.line(a, "logi.haul_start", "from", PaLogiPlan.placeName(o.from()), "to", PaLogiPlan.placeName(o.to())));
                phase = "load";
                run = from.isEmpty() ? null : new Run(from, this);
                if (run != null) run.noOverflow = true;
                lastHave = count(a, p);
            }
            case "load" -> {
                if (run != null) {
                    int r = run.load(a, p, -1);
                    if (r == 0) return;
                    run.close(a);
                }
                if (area != null && PaContainers.packFreeSlots(a) > 0 && pickUpLoose(a, p)) return;
                int got = count(a, p) - lastHave;
                if (got <= 0) {
                    if (tripsWanted > 0 && trips >= tripsWanted) { done(a, PaLogiTalk.line(a, "logi.stopped", "n", String.valueOf(moved))); return; }
                    if (!saidWait) { PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.haul_wait", "from", PaLogiPlan.placeName(o.from())), true); saidWait = true; }
                    waitUntil = a.tickCount + 20 * 30;
                    run = from.isEmpty() ? null : new Run(from, this);
                    if (run != null) run.noOverflow = true;
                    return;
                }
                saidWait = false;
                movingNow = got;
                run = new Run(to, this);
                phase = "unload";
            }
            case "unload" -> {
                int r = run.unload(a, p, movingNow);
                if (r == 0) return;
                if (r < 0) { fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a))); return; }
                run.close(a);
                trips++;
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "logi.haul_trip", "trip", String.valueOf(trips), "n", String.valueOf(moved)), true);
                if (tripsWanted > 0 && trips >= tripsWanted) { done(a, PaLogiTalk.line(a, "logi.stopped", "n", String.valueOf(moved))); return; }
                lastHave = count(a, p);
                run = from.isEmpty() ? null : new Run(from, this);
                if (run != null) run.noOverflow = true;
                phase = "load";
            }
            default -> phase = "start";
        }
    }

    @Nullable private ItemEntity loose;

    /** Things lying on the ground at the haul's start (a mine's spoil): walk over and pick them up. True while busy. */
    private boolean pickUpLoose(PlayerAssistantEntity a, Predicate<ItemStack> p) {
        if (loose == null || !loose.isAlive()) {
            loose = null;
            List<ItemEntity> l = a.level().getEntitiesOfClass(ItemEntity.class, new AABB(area).inflate(10), e -> e.isAlive() && p.test(e.getItem()));
            if (l.isEmpty()) return false;
            l.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(a)));
            loose = l.get(0);
        }
        if (a.distanceToSqr(loose) > 1.6 * 1.6) {
            if (a.tickCount % 10 == 0) a.getNavigation().moveTo(loose, 1.0);
            return true;
        }
        ItemStack left = a.insertItem(loose.getItem());
        if (left.isEmpty()) loose.discard(); else loose.setItem(left);
        loose = null;
        return true;
    }

    // ---- DEATH: fetch its player's things, then put them away or hand them over

    private void death(PlayerAssistantEntity a) {
        switch (phase) {
            case "start" -> {
                before.clear();
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) before.merge(PaLogiItems.id(s), s.getCount(), Integer::sum);
                a.enqueue(Job.recover(area.getX() + " " + area.getY() + " " + area.getZ()));
                phase = "engine";
                PaTalk.reply(a, "Going for your things. They'll go " + (to.isEmpty() ? "back to you" : "in " + PaLogistics.called(a, to.get(0), o.to())) + ".");
            }
            case "engine" -> {
                PaPlan.Step cur = a.plan.current;
                if (cur != null) cur.fail = "";
                Map<String, Integer> now = new HashMap<>();
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) now.merge(PaLogiItems.id(s), s.getCount(), Integer::sum);
                Set<String> gained = new LinkedHashSet<>();
                for (Map.Entry<String, Integer> e : now.entrySet()) if (e.getValue() > before.getOrDefault(e.getKey(), 0)) gained.add(e.getKey());
                if (gained.isEmpty()) { fail(a, "I couldn't find your things. They may have gone."); return; }
                outputs.clear();
                outputs.addAll(gained);
                if (to.isEmpty()) {
                    int n = 0;
                    for (String id : gained) n += now.get(id) - before.getOrDefault(id, 0);
                    moved = n;
                    given = 0;
                    phase = "give";
                    return;
                }
                run = new Run(to, this);
                phase = "unload";
            }
            case "unload" -> {
                int r = run.unload(a, s -> outputs.contains(PaLogiItems.id(s)), -1);
                if (r == 0) return;
                if (r < 0) { fail(a, PaLogiTalk.line(a, "logi.full_none", "chest", chestName(a))); return; }
                done(a, "Your things are in " + chestName(a) + ". " + moved + " in all.");
            }
            case "give" -> {
                ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp ? sp : null;
                if (owner == null) { done(a, "I've got your things. I'll hold on to them."); return; }
                if (a.distanceToSqr(owner) > 2.6 * 2.6) { if (a.tickCount % 15 == 0) a.getNavigation().moveTo(owner, 1.1); return; }
                Predicate<ItemStack> mineP = s -> outputs.contains(PaLogiItems.id(s));
                if (count(a, mineP) == 0 || given >= moved) { done(a, "There you go. All your things back."); return; }
                if (!PaLogistics.handFree(a)) return;
                PaLogistics.act(a, () -> { int g = handOne(a, owner, mineP, 64); given += g; return g > 0; });
            }
            default -> phase = "start";
        }
    }

    // ------------------------------------------------------------------ one round of visits: walk, lid up, stacks, lid down

    /** Visiting a list of containers in turn; moving stacks in (unload) or out (load); overflowing to the next one near. */
    static final class Run {
        final List<BlockPos> list;
        final PaLogiJob job;
        int idx, walk, stall, lidWait, batch;
        double best = Double.MAX_VALUE;
        @Nullable BlockPos at;
        boolean lid, noOverflow, saidFull;
        /** Do stacks taken out count as the job's moved (fetching), or only stacks put in (filling, moving, hauling)? */
        boolean countOut;

        Run(List<BlockPos> list, PaLogiJob job) {
            this.list = new ArrayList<>(list);
            this.job = job;
        }

        @Nullable
        BlockPos target() {
            return idx < list.size() ? list.get(idx) : null;
        }

        void close(PlayerAssistantEntity a) {
            if (lid && at != null) {
                PaContainers.lid(a, at, false);
                Container c = PaContainers.open(a, at);
                PaContainers.snapshot(a, at, c);
            }
            lid = false;
        }

        private void next(PlayerAssistantEntity a) {
            close(a);
            at = null;
            idx++;
            walk = 0;
            stall = 0;
            best = Double.MAX_VALUE;
        }

        /** Walk to the current one and open it: 1 there and open, 0 on the way, -1 gave up on it (moves to the next). */
        int arrive(PlayerAssistantEntity a) {
            if (at == null) {
                if (idx >= list.size()) return -1;
                at = list.get(idx);
                walk = 0;
                stall = 0;
                best = Double.MAX_VALUE;
            }
            if (!a.level().isLoaded(at) || !PaContainers.is(a.level(), at)) { next(a); return -1; }
            Vec3 c = Vec3.atCenterOf(at);
            double dx = a.getX() - c.x, dz = a.getZ() - c.z, dy = a.getY() - at.getY();
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h <= 2.4 && dy >= -2.2 && dy <= 1.8) {
                a.getNavigation().stop();
                a.getLookControl().setLookAt(c.x, c.y, c.z);
                if (!lid) {
                    String why = PaContainers.refuse(a, at);
                    if (why != null) { PaTalk.comment(a, 0, why); next(a); return -1; }
                    PaContainers.lid(a, at, true);
                    lid = true;
                    lidWait = 5;
                    PaLogistics.LAST.put(a, at);
                }
                if (lidWait > 0) { lidWait--; return 0; }
                return 1;
            }
            double d = a.distanceToSqr(c);
            if (walk % 20 == 0) a.walkTo(PaBrain.beside(a, at), 1.0);
            walk++;
            if (d < best - 0.5) { best = d; stall = 0; } else stall++;
            if (stall > 20 * 15 || walk > 20 * 120) {
                PaTalk.comment(a, 1, "I can't get to " + PaLogistics.called(a, at, null) + ". Trying the next.", true);
                next(a);
                return -1;
            }
            return 0;
        }

        /** Its pack's matching stacks in, at most {@code most} (-1 all): 1 done, 0 working, -1 nowhere left with room. */
        int unload(PlayerAssistantEntity a, Predicate<ItemStack> p, int most) {
            if (most == 0 || count(a, p) == 0 || (most > 0 && batch >= most)) { close(a); return 1; }
            if (idx >= list.size() && at == null) {
                if (noOverflow) return 1;
                BlockPos last = list.isEmpty() ? a.blockPosition() : list.get(list.size() - 1);
                ItemStack like = ItemStack.EMPTY;
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && p.test(s)) { like = s; break; }
                BlockPos more = PaLogistics.nextNear(a, last, list.isEmpty() ? "chest" : PaContainers.type(a.level(), last), list, like);
                if (more == null) return -1;
                if (!saidFull) PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.full", "chest", PaLogistics.called(a, last, null)), true);
                saidFull = true;
                list.add(more);
                if (!job.to.contains(more)) job.to.add(more);
            }
            int v = arrive(a);
            if (v <= 0) return 0;
            Container c = PaContainers.open(a, at);
            if (c == null) { next(a); return 0; }
            if (!PaLogistics.handFree(a)) return 0;
            int cap = most < 0 ? 64 : Math.min(64, most - batch);
            int[] n = {0};
            PaLogistics.act(a, () -> { n[0] = PaContainers.putOne(a, c, p, cap, false); return n[0] > 0; });
            if (n[0] > 0) {
                batch += n[0];
                if (!countOut) job.moved += n[0];
                return 0;
            }
            // nothing went in: this one is full (for these things)
            next(a);
            return 0;
        }

        /** Matching stacks out into its pack, at most {@code most} (-1 all): 1 done (enough, or the list ran out), 0 working, -1 its pack is full. */
        int load(PlayerAssistantEntity a, Predicate<ItemStack> p, int most) {
            if (most == 0 || (most > 0 && batch >= most)) { close(a); return 1; }
            if (idx >= list.size() && at == null) return 1;
            int v = arrive(a);
            if (v < 0) return 0;
            if (v == 0) return 0;
            Container c = PaContainers.open(a, at);
            if (c == null || PaContainers.count(c, p) == 0) { next(a); return 0; }
            if (!PaLogistics.handFree(a)) return 0;
            int cap = most < 0 ? 64 : Math.min(64, most - batch);
            int[] n = {0};
            PaLogistics.act(a, () -> { n[0] = PaContainers.takeOne(a, c, p, cap, 0, c.getContainerSize()); return n[0] > 0; });
            if (n[0] > 0) {
                batch += n[0];
                if (countOut) job.moved += n[0];
                return 0;
            }
            close(a);
            return -1;
        }
    }
}
