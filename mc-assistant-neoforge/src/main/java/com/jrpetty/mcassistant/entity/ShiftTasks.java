package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Shifts.Shift;
import com.jrpetty.mcassistant.entity.Shifts.Task;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [earning] What each paid shift's tasks are, done with the town's own mechanics, and how each is measured:
 * <ul>
 * <li><b>Counter</b>: the queue at a shop, café or tavern counter waits for the player instead of the keeper; serving
 *     the first in line from the counter screen is a task (ServiceQueue.servedByHand), against a par of twelve seconds
 *     at the front; out of turn is a mistake; a customer who gives up is a task failed.</li>
 * <li><b>Waiter</b>: a plate on the pass (Dining) is the player's: right-click the pass to take it (it is in the hand,
 *     marked), right-click its table to set it down there (Dining.setDown). The wrong table is a mistake; a plate lost
 *     goes back to the kitchen and the task fails.</li>
 * <li><b>Cook</b>: a ticket on the board (a queued line) is the player's: right-click its station to put it on (the
 *     makings out of the stores, as the kitchen's own cooks take them), and again when it is ready: too soon is not
 *     done yet, too late burns it (thrown out, made again, unpaid).</li>
 * <li><b>Farm hand</b>: a ripe crop in the town's fields: broken, its harvest goes to the stores and the seed to the
 *     hand; planted back on the plot, the task is done (left bare, it costs stars).</li>
 * <li><b>Hauler</b>: a workshop chest short of a making: load it at the storehouse (a deposit held for it), unload it
 *     into the chest; short loads are mistakes.</li>
 * <li><b>Smelter</b>: raw ore out of the stores (a deposit held), smelted wherever the player likes, the ingots back
 *     to the smithy chest; paid by the ingot.</li>
 * <li><b>Night watch</b>: the round of four posts about the town, a post a task; each monster killed in the town is a
 *     task as well.</li>
 * <li><b>Builder's hand</b>: a town building's missing blocks (cells of its drawing standing empty): the blocks out of
 *     the stores (a deposit held), each laid on its marked spot and set the way the drawing has it; elsewhere refused.</li>
 * </ul>
 * Everything a task holds (a line of an order, a load, a deposit) is given back when it ends any other way (release).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ShiftTasks {

    private ShiftTasks() {}

    /** Par walking pace (blocks a second) and the time a task takes beyond its walking (ticks). */
    static final double WALK = 3.5;
    static final long HANDLING = 100L;
    /** The counter's par at the front of the line (ticks), and how long after a ripe crop is broken it must be replanted. */
    static final long COUNTER_PAR = 240L, REPLANT = 600L;
    /** The window after a dish is ready before it burns (ticks; longer for a better cook). */
    static final long BURN_WINDOW = 120L, BURN_PER_LEVEL = 20L;
    /** Loads: the most a haul moves; ore smelted a task; blocks laid a task. */
    static final int HAUL_MOST = 32, SMELT_LOT = 8, SMELT_MOST = 16, BUILD_MOST = 3;
    /** How long the found places are kept before looking again (ticks). */
    static final long POST_FOR = 200L;

    public static void resetForTests() {
        POSTS.clear();
        SKIP.clear();
        FORGET.clear();
    }

    // ------------------------------------------------------------------ the posts

    /** Where a job is worked from in a town (the counter, the pass, the storehouse...), which counter it serves, and
     *  whether it has work now (and why not). */
    record Post(BlockPos pos, @Nullable ServiceRole served, boolean ready, String why) {}

    private record Found(long at, @Nullable Post post) {}

    private static final Map<String, Found> POSTS = new ConcurrentHashMap<>();

    /** The job's post in this town (cached a few seconds), or null if the town has nowhere to work it. */
    @Nullable
    static Post post(ServerLevel level, Villages.Village v, ShiftRole r, BlockPos near) {
        String key = v.id() + "/" + r.id;
        long now = level.getGameTime();
        Found f = POSTS.get(key);
        if (f != null && now - f.at() < POST_FOR && now >= f.at()) return f.post();
        Post p = findPost(level, v, r, near);
        POSTS.put(key, new Found(now, p));
        return p;
    }

    @Nullable
    private static Post findPost(ServerLevel level, Villages.Village v, ShiftRole r, BlockPos near) {
        UUID id = v.id();
        switch (r) {
            case COUNTER -> {
                ServicePoints.Point best = null;
                for (ServiceRole role : List.of(ServiceRole.SHOP, ServiceRole.CAFE, ServiceRole.TAVERN)) {
                    for (ServicePoints.Point pt : ServicePoints.of(level, id, role)) {
                        if (best == null || pt.pos().distSqr(near) < best.pos().distSqr(near)) best = pt;
                    }
                }
                if (best == null) return null;
                ServicePoints.Status st = ServicePoints.status(level, id, best.role(), best);
                return new Post(best.pos(), best.role(), st.open(), st.open() ? "" : st.words());
            }
            case WAITER, COOK -> {
                DiningPlaces.Place place = place(level, id, near);
                return place == null ? null : new Post(place.pass, place.kind.role, true, "");
            }
            case FARM -> {
                List<BlockPos> ripe = ripe(level, v, 1, null);
                BlockPos at = !ripe.isEmpty() ? ripe.get(0) : fieldCentre(v);
                if (at == null) return null;
                return new Post(at, null, !ripe.isEmpty(), ripe.isEmpty() ? "No crops are ripe just now." : "");
            }
            case HAUL -> {
                BlockPos store = storehouse(level, id);
                if (store == null) return null;
                return new Post(store, null, shortage(level, id) != null, "No workshop is short of anything just now.");
            }
            case SMELT -> {
                BlockPos store = storehouse(level, id);
                if (store == null) return null;
                Item ore = oreToSmelt(level, id);
                return new Post(store, null, ore != null, "No raw ore in the stores to smelt.");
            }
            case WATCH -> {
                return Villages.folkOf(id).size() < 3 ? null : new Post(v.centre(), null, true, "");
            }
            case BUILD -> {
                BlockPos store = storehouse(level, id);
                List<Gap> gaps = gaps(level, v);
                if (store == null || gaps.isEmpty()) return null;
                return new Post(store, null, true, "");
            }
        }
        return null;
    }

    /** The town's dining room nearest here (a restaurant first), or null. */
    @Nullable
    static DiningPlaces.Place place(ServerLevel level, UUID village, BlockPos near) {
        DiningPlaces.Place best = null;
        for (DiningPlaces.Place p : DiningPlaces.of(level, village)) {
            if (best == null || (p.kind == DiningPlaces.Kind.RESTAURANT) != (best.kind == DiningPlaces.Kind.RESTAURANT)
                    ? p.kind == DiningPlaces.Kind.RESTAURANT || best == null : p.pass.distSqr(near) < best.pass.distSqr(near)) best = p;
        }
        return best;
    }

    /** The storehouse's door (or the town's first store chest), or null. */
    @Nullable
    static BlockPos storehouse(ServerLevel level, UUID village) {
        StorehouseBlockEntity s = Storehouses.storeFor(level, village);
        if (s != null) return s.getBlockPos();
        List<BlockPos> chests = Villages.storeChests(level, village);
        return chests.isEmpty() ? null : chests.get(0);
    }

    // ------------------------------------------------------------------ finding the next task

    /** The next task for this shift, if there is one now. */
    static void next(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v) {
        long now = level.getGameTime();
        Task t = switch (s.role) {
            case COUNTER -> counterTask(level, s, now);
            case WAITER -> waiterTask(level, p, s, now);
            case COOK -> cookTask(level, p, s, now);
            case FARM -> farmTask(level, p, s, v, now);
            case HAUL -> haulTask(level, p, s, now);
            case SMELT -> smeltTask(level, p, s, now);
            case WATCH -> watchTask(level, p, s, v, now);
            case BUILD -> buildTask(level, p, s, v, now);
        };
        if (t != null) {
            s.task = t;
            p.displayClientMessage(Component.literal(t.text).withStyle(ChatFormatting.AQUA), true);
            level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.4F, 1.6F);
        }
    }

    /** What the HUD says while there is nothing to do. */
    static String idleWords(Shift s) {
        return switch (s.role) {
            case COUNTER -> "Nobody in line. Stay by the counter; the next customer is yours.";
            case WAITER -> "No plates on the pass. Wait by the pass.";
            case COOK -> "No tickets just now. Wait in the kitchen.";
            case FARM -> "No ripe plots just now. Wait by the fields.";
            case HAUL -> "No loads wanted just now. Wait by the storehouse.";
            case SMELT -> "No ore to smelt just now.";
            case WATCH -> "On to the next post.";
            case BUILD -> "Nothing to lay just now.";
        };
    }

    private static long walk(BlockPos a, BlockPos b) {
        return (long) (Math.sqrt(a.distSqr(b)) / WALK * 20);
    }

    // ---- the counter

    @Nullable
    private static Task counterTask(ServerLevel level, Shift s, long now) {
        List<UUID> line = ServiceQueue.lineAt(s.post);
        if (line.isEmpty() || !(level.getEntity(line.get(0)) instanceof VillageFolkEntity f)) return null;
        Task t = new Task("served", f.displayNameCap() + " is first in line. Open the counter (right-click it) and serve "
            + f.displayNameCap() + " from the Work tab.", s.post, now, COUNTER_PAR);
        t.folk = f.getUUID();
        t.entity = f.getId();
        t.who = f.displayNameCap();
        return t;
    }

    /** Is a player on a counter shift at this point, near enough to serve (the queue waits for it then)? */
    public static boolean manned(ServerLevel level, BlockPos point) {
        if (Shifts.ON.isEmpty()) return false;
        for (Shift s : Shifts.ON.values()) {
            if (s.role != ShiftRole.COUNTER || !s.post.equals(point)) continue;
            if (level.getPlayerByUUID(s.player) instanceof ServerPlayer p && p.distanceToSqr(point.getX() + 0.5, point.getY(), point.getZ() + 0.5) < 8 * 8) return true;
        }
        return false;
    }

    /** The counter screen's Serve: this customer served by the player's hand (out of turn: a mistake). What it says. */
    static String serve(ServerLevel level, ServerPlayer p, UUID folk) {
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.COUNTER) return "You're not on a counter shift.";
        List<UUID> line = ServiceQueue.lineAt(s.post);
        if (!line.contains(folk) || !(level.getEntity(folk) instanceof VillageFolkEntity f)) return "They've gone.";
        Task t = s.task;
        if (t == null) {
            t = counterTask(level, s, level.getGameTime());
            if (t == null) return "Nobody in line.";
            s.task = t;
        }
        if (!folk.equals(line.get(0))) {
            t.mistakes++;
            p.sendSystemMessage(Component.literal("Out of turn: " + t.who + " was first in line.").withStyle(ChatFormatting.RED));
        }
        ServiceQueue.servedByHand(folk, p.getName().getString());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Thank you!", "Lovely, ta.", "That's me sorted."));
        Shifts.done(level, p, s, t, 0, 1, "serving " + f.displayNameCap(), f);
        return "";
    }

    // ---- the dining room

    @Nullable
    private static Task waiterTask(ServerLevel level, ServerPlayer p, Shift s, long now) {
        DiningPlaces.Place place = place(level, s.village, s.post);
        if (place == null) return null;
        for (Dining.Order o : Dining.ORDERS.values()) {
            if (!o.place.anchor().equals(place.building.anchor()) || o.mode != Dining.Mode.TABLE) continue;
            for (int i = 0; i < o.lines.size(); i++) {
                Dining.Line l = o.lines.get(i);
                if (l.step != Dining.Step.PASS || l.got.isEmpty() || foodWaitsForDrink(o, i) || skipped(o, i, now)) continue;
                DiningPlaces.Table tb = place.table(o.table);
                if (tb == null) continue;
                l.step = Dining.Step.CARRYING;
                String dish = l.dishDef() == null ? Goods.name(l.got) : l.dishDef().name();
                Task t = new Task("served", "Table " + o.table + " wants: " + dish + ". Take the plate from the pass (right-click it) to table "
                    + o.table + ".", place.pass, now, walk(place.pass, tb.cell()) + walk(p.blockPosition(), place.pass) + HANDLING);
                t.order = o.id;
                t.line = i;
                t.table = o.table;
                t.placeAnchor = place.building.anchor().asLong();
                t.to = tb.cell();
                t.who = o.dinerName;
                return t;
            }
        }
        return null;
    }

    private static boolean foodWaitsForDrink(Dining.Order o, int i) {
        if (o.lines.get(i).drink()) return false;
        for (Dining.Line l : o.lines) if (l.drink() && (l.step == Dining.Step.QUEUED || l.step == Dining.Step.COOKING)) return true;
        return false;
    }

    /** A line the player's kitchen could not make just now: left to the town's own cooks for a while. */
    private static final Map<String, Long> SKIP = new ConcurrentHashMap<>();

    private static boolean skipped(Dining.Order o, int i, long now) {
        Long until = SKIP.get(o.id + "/" + i);
        return until != null && now < until;
    }

    @Nullable
    private static Task cookTask(ServerLevel level, ServerPlayer p, Shift s, long now) {
        DiningPlaces.Place place = place(level, s.village, s.post);
        if (place == null) return null;
        for (Dining.Order o : Dining.ORDERS.values()) {
            if (!o.place.anchor().equals(place.building.anchor())) continue;
            for (int i = 0; i < o.lines.size(); i++) {
                Dining.Line l = o.lines.get(i);
                DiningMenu.Dish d = l.dishDef();
                if (l.step != Dining.Step.QUEUED || d == null || skipped(o, i, now)) continue;
                BlockPos station = place.station(d.station());
                l.step = Dining.Step.COOKING;
                String where = Dining.stationWord(d.station());
                Task t = new Task("cooked", "Ticket: " + d.name() + " for " + (o.mode == Dining.Mode.TABLE ? "table " + o.table : o.dinerName)
                    + ". Cook it at the " + where + " (right-click it).", station, now,
                    walk(p.blockPosition(), station) + cookTicks(d, 0) + HANDLING);
                t.order = o.id;
                t.line = i;
                t.placeAnchor = place.building.anchor().asLong();
                t.who = o.dinerName;
                return t;
            }
        }
        return null;
    }

    /** How long the player's hand takes over a dish: its own time, less for a better cook. */
    static long cookTicks(DiningMenu.Dish d, int level) {
        long ticks = (long) (d.seconds() * 20 * Math.max(0.5, 1.1 - 0.1 * level));
        return Dining.QUICK ? Math.max(10, ticks / 8) : ticks;
    }

    // ---- the fields

    @Nullable
    private static Task farmTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        List<BlockPos> ripe = ripe(level, v, 8, p);
        if (ripe.isEmpty()) return null;
        BlockPos best = ripe.get(0);
        for (BlockPos c : ripe) if (c.distSqr(p.blockPosition()) < best.distSqr(p.blockPosition())) best = c;
        String crop = Goods.name(new ItemStack(level.getBlockState(best).getBlock().asItem())).toLowerCase(Locale.ROOT);
        Task t = new Task("harvested", "Harvest the ripe " + crop + " at the marked plot, then plant it again.", best, now,
            walk(p.blockPosition(), best) + 200);
        t.marks.add(best);
        return t;
    }

    /** Ripe crops on the town's farmland (its farmers' fields, its farms), not already a player's task. */
    static List<BlockPos> ripe(ServerLevel level, Villages.Village v, int most, @Nullable ServerPlayer asking) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : fields(v)) {
            int r = 10;
            for (int dx = -r; dx <= r && out.size() < most; dx++) {
                for (int dz = -r; dz <= r && out.size() < most; dz++) {
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!level.isLoaded(new BlockPos(x, c.getY(), z))) continue;
                    BlockPos crop = Fields.cropIn(level, x, c.getY(), z);
                    if (crop == null || out.contains(crop) || claimed(crop)) continue;
                    BlockState st = level.getBlockState(crop);
                    if (st.getBlock() instanceof CropBlock cb && cb.isMaxAge(st)) out.add(crop);
                }
            }
            if (out.size() >= most) break;
        }
        return out;
    }

    /** The middles of the town's fields: its farmers' plots and its farm buildings. */
    private static List<BlockPos> fields(Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == StationTask.FARM && a.workZone() != null && out.size() < 6) out.add(a.workZone().center());
        }
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if ((b.structure().contains("farm") || b.structure().contains("field")) && out.size() < 10) out.add(b.anchor());
        }
        return out;
    }

    @Nullable
    private static BlockPos fieldCentre(Villages.Village v) {
        List<BlockPos> f = fields(v);
        return f.isEmpty() ? null : f.get(0);
    }

    private static boolean claimed(BlockPos crop) {
        for (Shift s : Shifts.ON.values()) if (s.task != null && s.task.marks.contains(crop)) return true;
        return false;
    }

    // ---- hauling and smelting

    /** A workshop chest short of a making the stores have: {trade, making, count}, or null. */
    @Nullable
    private static Object[] shortage(ServerLevel level, UUID village) {
        for (Map.Entry<StationTask, List<WorkStock.Making>> e : WorkStock.MAKINGS.entrySet()) {
            BlockPos chest = WorkStock.chestOf(level, village, e.getKey());
            if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) continue;
            for (WorkStock.Making m : e.getValue()) {
                int have = 0;
                for (int i = 0; i < c.getContainerSize(); i++) if (m.is().test(c.getItem(i))) have += c.getItem(i).getCount();
                if (have >= m.keep() * WorkStock.LOW) continue;
                int stock = Market.stock(level, village, m.is());
                int n = Math.min(HAUL_MOST, Math.min(m.keep() - have, stock));
                if (n >= 4) return new Object[]{ e.getKey(), m, n, chest };
            }
        }
        return null;
    }

    @Nullable
    private static Task haulTask(ServerLevel level, ServerPlayer p, Shift s, long now) {
        Object[] sh = shortage(level, s.village);
        BlockPos store = storehouse(level, s.village);
        if (sh == null || store == null) return null;
        StationTask trade = (StationTask) sh[0];
        WorkStock.Making m = (WorkStock.Making) sh[1];
        int n = (int) sh[2];
        BlockPos chest = (BlockPos) sh[3];
        String where = trade.title.toLowerCase(Locale.ROOT) + "'s";
        Task t = new Task("hauled", "Fetch " + Goods.words(new ItemStack(m.sample()), n) + " from the storehouse (right-click it) and put "
            + (n == 1 ? "it" : "them") + " in the " + where + " chest.", store, now, walk(p.blockPosition(), store) + walk(store, chest) + 2 * HANDLING);
        t.item = m.sample();
        t.count = n;
        t.to = chest;
        return t;
    }

    /** Raw ore the stores hold enough of to smelt a lot, or null. */
    @Nullable
    private static Item oreToSmelt(ServerLevel level, UUID village) {
        for (Item ore : List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD)) {
            if (Market.stock(level, village, x -> x.is(ore)) >= SMELT_LOT) return ore;
        }
        return null;
    }

    private static Item ingotOf(Item ore) {
        return ore == Items.RAW_GOLD ? Items.GOLD_INGOT : ore == Items.RAW_COPPER ? Items.COPPER_INGOT : Items.IRON_INGOT;
    }

    @Nullable
    private static Task smeltTask(ServerLevel level, ServerPlayer p, Shift s, long now) {
        Item ore = oreToSmelt(level, s.village);
        BlockPos store = storehouse(level, s.village);
        if (ore == null || store == null) return null;
        int stock = Market.stock(level, s.village, x -> x.is(ore));
        int n = Math.min(SMELT_MOST, stock - stock % SMELT_LOT);
        BlockPos smithy = WorkStock.chestOf(level, s.village, StationTask.SMITH);
        BlockPos back = smithy != null ? smithy : store;
        Task t = new Task("smelted", "Collect " + Goods.words(new ItemStack(ore), n) + " at the storehouse (right-click it), smelt it in a furnace, and bring the "
            + n + " " + Goods.name(new ItemStack(ingotOf(ore))).toLowerCase(Locale.ROOT) + "s to the " + (smithy != null ? "smithy chest." : "storehouse."),
            store, now, walk(p.blockPosition(), store) + (long) n * 200 + walk(store, back) + 2 * HANDLING);
        t.item = ore;
        t.count = n;
        t.to = back;
        return t;
    }

    // ---- the night watch

    /** The watch's four posts round the town: north, east, south, west, at seven tenths of its reach, on the ground. */
    static List<BlockPos> posts(ServerLevel level, Villages.Village v) {
        int r = Math.max(12, Math.min(48, (int) (Villages.townReach(v.id()) * 0.7)));
        BlockPos c = v.centre();
        List<BlockPos> out = new ArrayList<>();
        int[][] at = { { 0, -r }, { r, 0 }, { 0, r }, { -r, 0 } };
        for (int[] d : at) {
            BlockPos col = new BlockPos(c.getX() + d[0], c.getY(), c.getZ() + d[1]);
            out.add(level.isLoaded(col) ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col) : col);
        }
        return out;
    }

    private static final String[] POST_NAMES = { "north", "east", "south", "west" };

    @Nullable
    private static Task watchTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        List<BlockPos> posts = posts(level, v);
        int i = Math.floorMod(s.round, posts.size());
        BlockPos at = posts.get(i);
        Task t = new Task("walked to the post", "Walk the round: the " + POST_NAMES[i] + " post (marked). Kill any monster you meet on the way.",
            at, now, walk(p.blockPosition(), at) + 60);
        t.marks.add(at);
        return t;
    }

    // ---- the builder's hand

    /** A missing block of a town building: where, its drawing's cell, the building's facing, and what to lay. */
    record Gap(Ledger.Building building, BuildGoal.Placement cell, Item item) {}

    private record Gaps(long at, List<Gap> gaps) {}

    private static final Map<UUID, Gaps> GAPS = new ConcurrentHashMap<>();

    /** The town buildings' missing blocks (empty cells of their drawings with ground beside them), cached half a minute. */
    static List<Gap> gaps(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Gaps g = GAPS.get(v.id());
        if (g != null && now - g.at() < 600 && now >= g.at()) return g.gaps();
        List<ItemStack> held = new ArrayList<>();
        StorehouseBlockEntity store = Storehouses.storeFor(level, v.id());
        if (store != null) for (int i = 0; i < store.getContainerSize(); i++) if (!store.getItem(i).isEmpty()) held.add(store.getItem(i));
        for (BlockPos pos : Villages.storeChests(level, v.id())) {
            if (level.getBlockEntity(pos) instanceof Container c && c != store) {
                for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) held.add(c.getItem(i));
            }
        }
        List<Gap> out = new ArrayList<>();
        if (!held.isEmpty()) {
            int looked = 0;
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (out.size() >= 12 || looked >= 6) break;
                if (!level.isLoaded(b.anchor()) || !BuildGoal.STRUCTURES.contains(b.structure())) continue;
                looked++;
                for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                    if (p.part() != BuildGoal.Part.BLOCK || !level.isLoaded(p.pos()) || !level.getBlockState(p.pos()).isAir()) continue;
                    if (level.getBlockState(p.pos().below()).isAir()) continue;
                    Item it = BuildGoal.choose(v.id(), p.style(), held);
                    if (it == null || !(it instanceof BlockItem)) continue;
                    out.add(new Gap(b, p, it));
                    if (out.size() >= 12) break;
                }
            }
        }
        GAPS.put(v.id(), new Gaps(now, out));
        return out;
    }

    @Nullable
    private static Task buildTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        List<Gap> gaps = gaps(level, v);
        BlockPos store = storehouse(level, s.village);
        if (gaps.isEmpty() || store == null) return null;
        Gap first = null;
        for (Gap g : gaps) if (!claimed(g.cell().pos()) && level.getBlockState(g.cell().pos()).isAir()) { first = g; break; }
        if (first == null) return null;
        Task t = new Task("laid", "", store, now, 0);
        for (Gap g : gaps) {
            if (t.marks.size() >= BUILD_MOST) break;
            if (g.item() != first.item() || !g.building().equals(first.building()) || claimed(g.cell().pos()) || !level.getBlockState(g.cell().pos()).isAir()) continue;
            t.marks.add(g.cell().pos());
            t.plan.add(g.cell());
        }
        int n = t.marks.size();
        t.item = first.item();
        t.count = n;
        t.facing = first.building().facing();
        t.to = first.cell().pos();
        t.text = "Collect " + Goods.words(new ItemStack(first.item()), n) + " at the storehouse (right-click it), then lay "
            + (n == 1 ? "it on the marked spot" : "them on the marked spots") + " at the " + Villages.spoken(first.building().structure()) + ".";
        t.par = walk(p.blockPosition(), store) + walk(store, first.cell().pos()) + 160L * n + HANDLING;
        return t;
    }

    // ------------------------------------------------------------------ every second, while a task is on

    static void watch(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        long now = level.getGameTime();
        switch (s.role) {
            case COUNTER -> {
                if (t.folk != null && !ServiceQueue.lineAt(s.post).contains(t.folk)) {
                    String who = t.who;
                    Shifts.failed(level, p, s, who + " gave up waiting and left without being served.");
                }
            }
            case COOK -> {
                if (t.leg == 1 && t.target != null) {
                    level.sendParticles(now >= t.readyAt ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                        t.target.getX() + 0.5, t.target.getY() + 1.1, t.target.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
                    if (now > t.burnAt && now - t.burnAt < 25) {
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, t.target.getX() + 0.5, t.target.getY() + 1.1, t.target.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.01);
                    }
                }
            }
            case FARM -> {
                if (t.leg == 1 && now - t.since > REPLANT && t.target != null) {
                    Shifts.done(level, p, s, t, 2, 1, "harvesting a plot, not replanted", null);
                } else if (t.leg == 0 && t.target != null && !(level.getBlockState(t.target).getBlock() instanceof CropBlock)) {
                    s.task = null;                                                  // somebody else harvested it: no fault
                }
            }
            case WATCH -> {
                if (t.target != null && p.blockPosition().distManhattan(new net.minecraft.core.Vec3i(t.target.getX(), p.getBlockY(), t.target.getZ())) <= 4) {
                    s.round++;
                    Shifts.done(level, p, s, t, 0, 1, "the " + POST_NAMES[Math.floorMod(s.round - 1, 4)] + " post", null);
                }
            }
            case BUILD -> {
                if (t.leg == 1) {
                    for (int i = t.marks.size() - 1; i >= 0; i--) {
                        if (!level.getBlockState(t.marks.get(i)).isAir()) {               // a town builder laid it meanwhile
                            t.marks.remove(i);
                            t.plan.remove(i);
                        }
                    }
                    if (t.marks.isEmpty()) finishBuild(level, p, s, t);
                }
                forgetPlaced(level);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the player's hands at work

    /** Right-clicks at work: the pass, a table, a station, the storehouse, a chest. Ahead of the blocks' own use. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p) || Shifts.ON.isEmpty()) return;
        Shift s = Shifts.of(p);
        if (s == null || s.task == null) return;
        boolean used;
        try {
            used = use(level, p, s, s.task, e.getPos());
        } catch (RuntimeException ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[earning] a shift's click failed", ex);
            used = false;
        }
        if (used) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** One click at work: true if it was the task's. */
    static boolean use(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        return switch (s.role) {
            case WAITER -> waiterClick(level, p, s, t, at);
            case COOK -> cookClick(level, p, s, t, at);
            case HAUL, SMELT, BUILD -> t.leg == 0 ? load(level, p, s, t, at) : t.to != null && near(at, t.to, 1) && s.role != ShiftRole.BUILD && unload(level, p, s, t);
            default -> false;
        };
    }

    private static boolean near(BlockPos a, BlockPos b, int d) {
        return Math.abs(a.getX() - b.getX()) <= d && Math.abs(a.getY() - b.getY()) <= d + 1 && Math.abs(a.getZ() - b.getZ()) <= d;
    }

    @Nullable
    private static DiningPlaces.Place placeOf(ServerLevel level, UUID village, long anchor) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.anchor().asLong() == anchor) return DiningPlaces.place(level, village, b);
        return null;
    }

    private static boolean waiterClick(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        Dining.Order o = Dining.ORDERS.get(t.order);
        DiningPlaces.Place place = placeOf(level, s.village, t.placeAnchor);
        if (o == null || place == null || t.line < 0 || t.line >= o.lines.size()) { s.task = null; return false; }
        Dining.Line l = o.lines.get(t.line);
        if (t.leg == 0) {
            if (!near(at, place.pass, 1)) return false;
            ItemStack plate = l.got.copy();
            CustomData.update(DataComponents.CUSTOM_DATA, plate, tag -> tag.putString(Shifts.MARK, "plate:" + o.id + ":" + t.line));
            l.got = ItemStack.EMPTY;
            if (!p.getInventory().add(plate)) {
                l.got = strip(plate);
                p.sendSystemMessage(Component.literal("Your hands are full: make room in your pack for the plate.").withStyle(ChatFormatting.RED));
                return true;
            }
            t.leg = 1;
            t.target = t.to;
            t.text = "Carry the " + Goods.name(plate).toLowerCase(Locale.ROOT) + " to table " + t.table + " and right-click it.";
            level.playSound(null, place.pass, SoundEvents.DECORATED_POT_PLACE, SoundSource.BLOCKS, 0.5F, 1.4F);
            return true;
        }
        DiningPlaces.Table clicked = null;
        for (DiningPlaces.Table tb : place.tables) if (near(at, tb.cell(), 1)) clicked = tb;
        if (clicked == null) return false;
        if (clicked.number() != t.table) {
            t.mistakes++;
            p.displayClientMessage(Component.literal("That's table " + clicked.number() + ": this plate is for table " + t.table + ".").withStyle(ChatFormatting.RED), true);
            return true;
        }
        ItemStack plate = takeMarked(p, "plate:" + o.id + ":" + t.line);
        if (plate.isEmpty()) {
            l.got = ItemStack.EMPTY;
            l.step = Dining.Step.QUEUED;
            Shifts.failed(level, p, s, "The plate for table " + t.table + " is gone: the kitchen makes it again, and the task is unpaid.");
            return true;
        }
        l.got = strip(plate);
        if (!Dining.setDown(level, place, clicked, o, l)) {
            if (!o.player) Diners.servedInHand(level, o, l);
            else if (level.getPlayerByUUID(o.diner) instanceof ServerPlayer diner) {
                if (!diner.getInventory().add(l.got.copy())) diner.drop(l.got.copy(), false);
                l.got = ItemStack.EMPTY;
                l.step = Dining.Step.EATEN;
            }
        }
        if (l.step.ordinal() < Dining.Step.SERVED.ordinal()) l.step = Dining.Step.SERVED;
        Dining.save(level);
        VillageFolkEntity diner = !o.player && level.getEntity(o.diner) instanceof VillageFolkEntity f ? f : null;
        Shifts.done(level, p, s, t, 0, 1, "serving table " + t.table, diner);
        return true;
    }

    private static boolean cookClick(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        if (t.target == null || !near(at, t.target, 0)) return false;
        Dining.Order o = Dining.ORDERS.get(t.order);
        DiningPlaces.Place place = placeOf(level, s.village, t.placeAnchor);
        Villages.Village v = Villages.get(s.village);
        if (o == null || place == null || v == null || t.line < 0 || t.line >= o.lines.size()) { s.task = null; return false; }
        Dining.Line l = o.lines.get(t.line);
        DiningMenu.Dish d = l.dishDef();
        if (d == null) { s.task = null; return true; }
        long now = level.getGameTime();
        int lv = Shifts.level(p, s.role);
        if (t.leg == 0) {
            ItemStack made = Dining.produce(level, v, place, d, null);
            if (made.isEmpty()) {
                l.step = Dining.Step.QUEUED;
                SKIP.put(o.id + "/" + t.line, now + 1200);
                s.task = null;
                p.sendSystemMessage(Component.literal("The stores are out of the makings for " + d.name().toLowerCase(Locale.ROOT)
                    + ": the ticket goes back to the kitchen. Not your fault.").withStyle(ChatFormatting.YELLOW));
                return true;
            }
            l.got = made;
            l.cook = p.getName().getString();
            l.at = t.target.immutable();
            t.leg = 1;
            t.readyAt = now + cookTicks(d, lv);
            t.burnAt = t.readyAt + BURN_WINDOW + BURN_PER_LEVEL * lv;
            t.text = "Cooking " + d.name().toLowerCase(Locale.ROOT) + " at the " + Dining.stationWord(d.station())
                + ". Right-click it again when it's READY, before it burns.";
            level.playSound(null, t.target, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.7F, 1.0F);
            return true;
        }
        if (now < t.readyAt) {
            p.displayClientMessage(Component.literal("Not done yet: " + Shifts.secs(t.readyAt - now) + " more.").withStyle(ChatFormatting.YELLOW), true);
            return true;
        }
        if (now > t.burnAt) {
            l.got = ItemStack.EMPTY;
            l.step = Dining.Step.QUEUED;
            level.sendParticles(ParticleTypes.LARGE_SMOKE, t.target.getX() + 0.5, t.target.getY() + 1.1, t.target.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.01);
            Shifts.failed(level, p, s, "Burnt! The " + d.name().toLowerCase(Locale.ROOT) + " is thrown out and goes back on the board. Unpaid.");
            return true;
        }
        long late = now - t.readyAt, window = t.burnAt - t.readyAt;
        int lost = late > window * 2 / 3 ? 1 : 0;                                // caught at the last moment: a little dry
        l.quality = lost == 0 && lv >= 3 ? 2 : lost == 0 ? 1 : 0;
        l.step = Dining.Step.PASS;
        Dining.save(level);
        level.playSound(null, place.pass, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.35F, 1.6F);
        Shifts.done(level, p, s, t, lost, 1, "cooking " + d.name().toLowerCase(Locale.ROOT), null);
        return true;
    }

    /** At the storehouse: the load (the goods, the ore, the blocks) out of the stores into the pack, a deposit held. */
    private static boolean load(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        if (t.target == null || t.item == null || !(near(at, t.target, 3) || Villages.storeChests(level, s.village).contains(at))) return false;
        Villages.Village v = Villages.get(s.village);
        if (v == null) return false;
        Item item = t.item;
        ItemStack one = new ItemStack(item);
        int deposit = Market.bidLot(level, s.village, one, t.count);
        if (Market.coinsHeld(p) < deposit) {
            p.sendSystemMessage(Component.literal("The storekeeper wants a deposit of " + deposit + " coin for the load, back when it's delivered.")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        if (!TownWork.take(level, v, x -> x.is(item) && !Shifts.isShiftItem(x), t.count)) {
            s.task = null;
            p.sendSystemMessage(Component.literal("The stores have run short of that meanwhile. Wait for the next task.").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        if (deposit > 0) Market.payOut(p, deposit);
        t.deposit = deposit;
        int left = t.count;
        while (left > 0) {
            int k = Math.min(left, one.getMaxStackSize());
            ItemStack load = new ItemStack(item, k);
            CustomData.update(DataComponents.CUSTOM_DATA, load, tag -> tag.putString(Shifts.MARK, "load"));
            if (!p.getInventory().add(load)) p.drop(load, false);
            left -= k;
        }
        t.leg = 1;
        t.target = s.role == ShiftRole.BUILD ? t.marks.get(0) : t.to;
        if (s.role == ShiftRole.HAUL) t.text = "Put the " + Goods.words(one, t.count) + " in the marked chest (right-click it).";
        else if (s.role == ShiftRole.SMELT) t.text = "Smelt the " + Goods.words(one, t.count) + " in a furnace, then bring the ingots to the marked chest.";
        else t.text = "Lay the " + Goods.words(one, t.count) + " on the marked spots.";
        level.playSound(null, at, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, 1.1F);
        return true;
    }

    /** At the chest: the load (or the ingots) out of the pack into it, the deposit back, the task paid. */
    private static boolean unload(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        if (t.to == null || t.item == null || !(level.getBlockEntity(t.to) instanceof Container c)) return false;
        Item bring = s.role == ShiftRole.SMELT ? ingotOf(t.item) : t.item;
        int moved = 0;
        for (int i = 0; i < p.getInventory().getContainerSize() && moved < t.count; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(bring)) continue;
            if (s.role == ShiftRole.HAUL && !Shifts.isShiftItem(st)) continue;
            int k = Math.min(st.getCount(), t.count - moved);
            ItemStack put = strip(st.copyWithCount(k));
            ItemStack left = Stacking.insert(c, put);
            if (!left.isEmpty()) {
                Villages.Village v = Villages.get(s.village);
                if (v != null) Market.intoStores(level, s.village, left);
            }
            st.shrink(k);
            moved += k;
        }
        p.getInventory().setChanged();
        if (moved <= 0) {
            p.displayClientMessage(Component.literal("You've none of the " + Goods.name(new ItemStack(bring)).toLowerCase(Locale.ROOT) + " to put in.")
                .withStyle(ChatFormatting.RED), true);
            return true;
        }
        if (moved < t.count) t.mistakes++;
        int back = (int) Math.round(t.deposit * moved / (double) t.count);
        if (back > 0) Earnings.giveCoins(p, back);
        if (t.deposit - back > 0) Ledger.addCoins(s.village, t.deposit - back);       // what was not brought, kept by the stores
        if (s.role == ShiftRole.SMELT) returnUnsmelted(level, p, s, t);
        level.playSound(null, t.to, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
        double units = s.role == ShiftRole.SMELT ? moved : 1.0 * moved / t.count;
        Shifts.done(level, p, s, t, 0, units, s.role == ShiftRole.SMELT ? "smelting " + moved + " ingots"
            : "hauling " + Goods.words(new ItemStack(bring), moved), null);
        return true;
    }

    /** Ore the smelter brought back unsmelted goes back to the stores. */
    private static void returnUnsmelted(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!Shifts.isShiftItem(st)) continue;
            Market.intoStores(level, s.village, strip(st.copy()));
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ the fields: harvest and replant

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel level) || !(e.getPlayer() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.FARM || s.task == null) return;
        Task t = s.task;
        BlockState st = e.getState();
        if (!(st.getBlock() instanceof CropBlock cb)) return;
        if (t.leg == 0 && e.getPos().equals(t.target)) {
            t.leg = 1;
            t.since = level.getGameTime();
            t.item = st.getBlock().asItem();
            t.text = "Now plant the " + Goods.name(new ItemStack(t.item)).toLowerCase(Locale.ROOT) + " back on the same plot.";
        } else if (!cb.isMaxAge(st) && PlayerServices.townAt(level, e.getPos()) != null) {
            t.mistakes++;
            p.displayClientMessage(Component.literal("That crop wasn't ripe yet.").withStyle(ChatFormatting.RED), true);
        }
    }

    /** The marked plot's harvest: into the town's stores, all but one seed, which goes to the hand to plant again. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getBreaker() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.FARM || s.task == null || !e.getPos().equals(s.task.target)) return;
        ServerLevel level = e.getLevel();
        Item seed = e.getState().getBlock().asItem();
        boolean handed = false;
        for (ItemEntity ie : e.getDrops()) {
            ItemStack st = ie.getItem().copy();
            if (!handed && st.is(seed)) {
                handed = true;
                if (!p.getInventory().add(st.split(1))) p.drop(new ItemStack(seed), false);
            }
            if (!st.isEmpty()) Market.intoStores(level, s.village, st);
        }
        if (!handed) {
            ItemStack one = new ItemStack(seed);
            if (!p.getInventory().add(one)) p.drop(one, false);
        }
        e.getDrops().clear();
    }

    /** A block set down at work: the replanted plot, or a builder's block on (or off) its mark. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.task == null) return;
        Task t = s.task;
        BlockState placed = e.getPlacedBlock();
        if (s.role == ShiftRole.FARM && t.leg == 1 && e.getPos().equals(t.target) && placed.getBlock() instanceof CropBlock) {
            Shifts.done(level, p, s, t, 0, 1, "harvesting and replanting a plot", null);
            return;
        }
        if (s.role != ShiftRole.BUILD || t.leg != 1 || t.item == null || placed.getBlock().asItem() != t.item) return;
        int i = t.marks.indexOf(e.getPos());
        if (i < 0) {
            if (t.to != null && t.to.distSqr(e.getPos()) < 24 * 24) {
                e.setCanceled(true);
                t.mistakes++;
                p.displayClientMessage(Component.literal("Not there: lay it on a marked spot.").withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        BuildGoal.Placement cell = t.plan.get(i);
        BlockState set = BuildGoal.orient(placed, cell, t.facing == null ? Direction.NORTH : t.facing, level, e.getPos());
        if (set != placed) level.setBlock(e.getPos(), set, 3);
        FORGET.add(new Object[]{ level, e.getPos().immutable() });
        t.marks.remove(i);
        t.plan.remove(i);
        t.laid++;
        if (t.marks.isEmpty()) finishBuild(level, p, s, t);
    }

    /** The blocks laid: the deposit back (less any unlaid block kept), paid by the block. */
    private static void finishBuild(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        int laid = t.laid;
        int returned = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!Shifts.isShiftItem(st) || t.item == null || !st.is(t.item)) continue;
            returned += st.getCount();
            Market.intoStores(level, s.village, strip(st.copy()));
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        int covered = Math.min(t.count, laid + returned);
        int back = (int) Math.round(t.deposit * covered / (double) Math.max(1, t.count));
        if (back > 0) Earnings.giveCoins(p, back);
        if (t.deposit - back > 0) Ledger.addCoins(s.village, t.deposit - back);
        GAPS.remove(s.village);
        if (laid <= 0) {
            s.task = null;
            return;
        }
        Shifts.done(level, p, s, t, 0, laid, "laying " + laid + (laid == 1 ? " block" : " blocks"), null);
    }

    /** Blocks laid for the town, not the player's own: off the player-blocks book (it writes them down after us). */
    private static final List<Object[]> FORGET = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static void forgetPlaced(ServerLevel level) {
        for (Object[] f : FORGET) {
            if (f[0] == level) PlayerBlocks.forget(level, (BlockPos) f[1]);
        }
        FORGET.clear();
    }

    // ------------------------------------------------------------------ the night watch's kills

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getEntity().level() instanceof ServerLevel level) || !(e.getEntity() instanceof Enemy)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.WATCH) return;
        LivingEntity dead = e.getEntity();
        Villages.Village v = PlayerServices.townAt(level, dead.blockPosition());
        if (v == null || !v.id().equals(s.village)) return;
        Task patrol = s.task;
        Task kill = new Task("killed", "", dead.blockPosition(), level.getGameTime(), 20);
        Shifts.done(level, p, s, kill, 0, 1, "a " + Quests.kind(dead).replaceAll("s$", "") + " on watch", null);
        s.task = patrol;
    }

    // ------------------------------------------------------------------ giving back

    /** A task ended any way but done: its line back to the kitchen, its load back to the stores (or its deposit kept). */
    static void release(ServerLevel level, @Nullable ServerPlayer p, Shift s, Task t) {
        Dining.Order o = t.order >= 0 ? Dining.ORDERS.get(t.order) : null;
        Dining.Line l = o != null && t.line >= 0 && t.line < o.lines.size() ? o.lines.get(t.line) : null;
        switch (s.role) {
            case WAITER -> {
                if (l == null) return;
                ItemStack plate = p == null ? ItemStack.EMPTY : takeMarked(p, "plate:" + t.order + ":" + t.line);
                if (t.leg == 0) l.step = Dining.Step.PASS;
                else if (!plate.isEmpty()) { l.got = strip(plate); l.step = Dining.Step.PASS; }
                else { l.got = ItemStack.EMPTY; l.step = Dining.Step.QUEUED; }
                Dining.save(level);
            }
            case COOK -> {
                if (l == null) return;
                if (t.leg == 0 || l.got.isEmpty() || level.getGameTime() > t.burnAt) { l.got = ItemStack.EMPTY; l.step = Dining.Step.QUEUED; }
                else l.step = Dining.Step.PASS;
                Dining.save(level);
            }
            case HAUL, SMELT, BUILD -> {
                if (t.leg != 1) return;
                int back = 0;
                if (p != null) {
                    for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                        ItemStack st = p.getInventory().getItem(i);
                        if (!Shifts.isShiftItem(st)) continue;
                        back += st.getCount();
                        Market.intoStores(level, s.village, strip(st.copy()));
                        p.getInventory().setItem(i, ItemStack.EMPTY);
                    }
                }
                int refund = (int) Math.round(t.deposit * Math.min(t.count, back) / (double) Math.max(1, t.count));
                if (p != null && refund > 0) Earnings.giveCoins(p, refund);
                if (t.deposit - refund > 0) {
                    Ledger.addCoins(s.village, t.deposit - refund);
                    if (p != null) p.sendSystemMessage(Component.literal("The load wasn't delivered: the stores keep " + (t.deposit - refund)
                        + " coin of your deposit for what is missing.").withStyle(ChatFormatting.RED));
                }
            }
            default -> { }
        }
    }

    /** The player is gone: what its task held is given back without it. */
    static void releaseOffline(ServerLevel level, Shift s, Task t) {
        release(level, null, s, t);
    }

    // ------------------------------------------------------------------ the marked things in hand

    /** Out of the pack: the thing a shift marked with this. Empty if it is not there (eaten, dropped, given away). */
    static ItemStack takeMarked(ServerPlayer p, String mark) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            CustomData d = st.get(DataComponents.CUSTOM_DATA);
            if (d == null || !mark.equals(d.copyTag().getString(Shifts.MARK))) continue;
            ItemStack out = st.copyWithCount(1);
            st.shrink(1);
            p.getInventory().setChanged();
            return out;
        }
        return ItemStack.EMPTY;
    }

    /** The shift's mark taken off a thing. */
    static ItemStack strip(ItemStack s) {
        ItemStack out = s.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, out, tag -> tag.remove(Shifts.MARK));
        CustomData d = out.get(DataComponents.CUSTOM_DATA);
        if (d != null && d.isEmpty()) out.remove(DataComponents.CUSTOM_DATA);
        return out;
    }

    // ------------------------------------------------------------------ the counter screen's Work tab

    /** The Work tab at a counter: this player's shift here (the line, each customer's wait, Serve), or the job offered. */
    static CompoundTag workTag(ServicePoints.Visit visit) {
        ServerLevel level = visit.level();
        CompoundTag t = new CompoundTag();
        ServerPlayer p = visit.player();
        if (p == null || visit.point() == null) return t;
        Shift s = Shifts.of(p);
        boolean here = s != null && s.role == ShiftRole.COUNTER && s.post.equals(visit.point().pos());
        t.putBoolean("onShift", here);
        if (here) {
            t.putString("title", s.title());
            t.putInt("tasks", s.tasks);
            t.putInt("pay", s.pay);
            t.putInt("tips", s.tips);
            t.putLong("ends", s.ends);
            t.putLong("now", level.getGameTime());
            net.minecraft.nbt.ListTag line = new net.minecraft.nbt.ListTag();
            int i = 0;
            for (UUID id : ServiceQueue.lineAt(visit.point().pos())) {
                if (!(level.getEntity(id) instanceof VillageFolkEntity f)) continue;
                CompoundTag c = new CompoundTag();
                c.putString("id", id.toString());
                c.putString("name", f.displayNameCap());
                c.putFloat("patience", ServiceQueue.patience(id, level.getGameTime()));
                c.putString("wants", ServiceQueue.wants(id));
                c.putBoolean("first", i++ == 0);
                line.add(c);
            }
            t.put("line", line);
            return t;
        }
        Villages.Village v = visit.village();
        for (Shifts.Offer o : Shifts.offers(level, v, p)) {
            if (o.role() != ShiftRole.COUNTER) continue;
            t.putString("title", o.title());
            t.putString("hours", o.hours());
            t.putString("pay", String.format(Locale.ROOT, "about %.1f coin a task, %d a day", o.perTask(), o.perDay()));
            t.putString("why", s != null ? "You're on a shift already." : o.whyNot());
            t.putString("level", com.jrpetty.mcassistant.village.EarnMath.LEVELS[Shifts.level(p, ShiftRole.COUNTER)]);
            net.minecraft.nbt.ListTag how = new net.minecraft.nbt.ListTag();
            for (String h : ShiftRole.COUNTER.howTo) how.add(net.minecraft.nbt.StringTag.valueOf(h));
            t.put("howTo", how);
        }
        return t;
    }
}
