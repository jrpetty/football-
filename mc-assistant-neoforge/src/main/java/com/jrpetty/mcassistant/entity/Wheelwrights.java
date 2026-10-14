package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [roles-e] The wheelwright: a post, not a trade (its holder keeps its own trade and gives the wagon part of its day),
 * in a town that has a wagon or one on the bench.
 *
 * <ul>
 * <li><b>Who.</b> The town reckons it each day from who is best at wood and iron (a hand of the shop's bench, a
 *     woodcutter, a smith; a hard worker), never the elder, the watch or a hand whose day is out of the town, and keeps
 *     the one an interview chose while it is fit (InterviewPosts, kind WHEELWRIGHT; Interviews.daily).</li>
 * <li><b>A wagon built as a work order.</b> When the town wants a wagon (Wagons.build: its first, or a better one for
 *     its age) and the stores hold the makings, they are set by for it at once, out of the stores, and held in the
 *     order; the wheelwright then builds it at the bench in its shed (blueprints/wheelwright.txt), or by the yard while
 *     there is none, over several visits by day (PostStints: a stint, then back to its own trade): a handcart in two
 *     visits or so, a wagon in three, a covered wagon in four. Done, the old wagon is broken up for its planks and the
 *     new one stands in the yard.</li>
 * <li><b>Mending.</b> A wagon home in its yard battered below nine tenths of its strength is mended by the wheelwright
 *     at its side: two planks from the stores a step, and iron for the fittings every third step of a drawn wagon's.
 *     With no planks (or iron) in the stores it waits, and says so.</li>
 * <li><b>Trouble.</b> The order and its makings are kept in the town's books: a restart, a wheelwright dead or gone
 *     mid-build, and the next wheelwright takes the half-made wagon up where it was left; an order nobody has touched
 *     in ten days is given up and its makings go back to the stores. Nothing is lost and nothing made from nothing.</li>
 * <li><b>Its pay.</b> An office's allowance on its wage (Offices). <b>Its dress.</b> A leather jacket and a flat cap
 *     (Weave). <b>Its talk.</b> "How's the wagon coming on?" (Trade, Shop): the order, the wagon's state, or what the
 *     stores are short of for the next.</li>
 * </ul>
 * Cost: once a second a look at each loaded town's order and wagon (map reads); the holder looked up at most every
 * five seconds a town.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Wheelwrights {

    private Wheelwrights() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The post's key in the interviews' books, and its stints'. */
    static final String POST = "wheelwright";
    /** The wheelwright's shed (blueprints/wheelwright.txt). */
    public static final String SHED = "wheelwright";
    /** A town this big, with a wagon or one on the bench, puts up the shed. */
    static final int SHED_FROM_FOLK = 10;
    /** A stint at the bench (ticks), and the rest after it: a wagon is built over several visits. */
    static final long STINT = 2400L, REST = 3600L;
    /** Called this long (ticks) without reaching the shed's bench: it works by the yard instead. */
    static final long STUCK = 900L;
    /** The wheelwright works by day: from the morning bell till dusk. */
    static final long DAY_FROM = 1000L, DAY_TILL = 11500L;
    /** A wagon battered below this share of its strength is mended. */
    static final double MEND_BELOW = 0.9;
    /** A step of mending: so many seconds' work, so many planks, so much strength back; iron every third step. */
    static final int MEND_STEP = 8, MEND_PLANKS = 2, IRON_EVERY = 3;
    static final float MEND_HP = 4.0F;
    /** An order nobody has worked at for this many days is given up, its makings back to the stores. */
    static final int GIVE_UP_DAYS = 10;
    /** The order's progress is written into the books every so many seconds of work (and at the end of each stint). */
    static final int SAVE_EVERY = 5;

    private static final String ORDER = "wheel.order";       // KIND|done|need|visits|opened|last|by|makings
    private static final String BOOK = "wheel.book";         // built|mended|planks|iron

    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);

    /** The work each wagon takes, in seconds of the wheelwright's own hands (a stint gives a hundred or so). */
    static int work(WagonKind k) {
        return switch (k) {
            case HANDCART -> 150;
            case WAGON -> 300;
            case COVERED -> 450;
        };
    }

    // ------------------------------------------------------------------ the order

    /** The town's wagon on the bench: what, how far on, over how many visits, since when, by whom, and its makings. */
    static final class Order {
        final WagonKind kind;
        final int need;
        final long opened;
        final List<ItemStack> makings;
        int done, visits;
        long last;
        String by;

        Order(WagonKind kind, int done, int need, int visits, long opened, long last, String by, List<ItemStack> makings) {
            this.kind = kind;
            this.done = done;
            this.need = need;
            this.visits = visits;
            this.opened = opened;
            this.last = last;
            this.by = by;
            this.makings = makings;
        }

        String encode() {
            StringBuilder m = new StringBuilder();
            for (ItemStack s : makings) {
                if (s.isEmpty()) continue;
                if (m.length() > 0) m.append(';');
                m.append(BuiltInRegistries.ITEM.getKey(s.getItem())).append('*').append(s.getCount());
            }
            return String.join("|", kind.name(), Integer.toString(done), Integer.toString(need), Integer.toString(visits),
                Long.toString(opened), Long.toString(last), by.replace("|", " "), m.toString());
        }

        @Nullable
        static Order decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            WagonKind k = p.length >= 8 ? WagonKind.byName(p[0]) : null;
            if (k == null) return null;
            try {
                List<ItemStack> makings = new ArrayList<>();
                for (String one : p[7].split(";")) {
                    int star = one.lastIndexOf('*');
                    if (star <= 0) continue;
                    ResourceLocation id = ResourceLocation.tryParse(one.substring(0, star));
                    Item it = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                    int n = Integer.parseInt(one.substring(star + 1));
                    if (it != null && it != Items.AIR && n > 0) makings.add(new ItemStack(it, n));
                }
                return new Order(k, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Long.parseLong(p[4]),
                    Long.parseLong(p[5]), p[6], makings);
            } catch (RuntimeException e) {
                LOG.warn("[MCA-WHEEL] an order that could not be read was left: {}", s);
                return null;
            }
        }

        int count(Item it) {
            int n = 0;
            for (ItemStack s : makings) if (s.is(it)) n += s.getCount();
            return n;
        }
    }

    /** The open orders as read, by town (the books are the truth; this saves reading them each second). */
    private static final Map<UUID, Order> OPEN = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> NONE = ConcurrentHashMap.newKeySet();
    /** Each town's wheelwright, looked up at most every five seconds: {when, who}. */
    private static final Map<UUID, Object[]> HOLDER = new ConcurrentHashMap<>();
    /** What the wheelwright is held up by, by town (the books and the talk); logged once when it changes. */
    private static final Map<UUID, String> STATUS = new ConcurrentHashMap<>();
    /** The planks and iron a mending has used so far, by town: {planks, iron}. */
    private static final Map<UUID, int[]> MENDING = new ConcurrentHashMap<>();
    /** The shed's bench, by town: {anchor, bench}. */
    private static final Map<UUID, BlockPos[]> BENCH = new ConcurrentHashMap<>();

    public static void resetForTests() {
        OPEN.clear();
        NONE.clear();
        HOLDER.clear();
        STATUS.clear();
        MENDING.clear();
        BENCH.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        resetForTests();
    }

    /** The town's order on the bench, or null. */
    @Nullable
    static Order order(UUID id) {
        Order o = OPEN.get(id);
        if (o != null || NONE.contains(id)) return o;
        o = Order.decode(Ledger.note(id, ORDER));
        if (o == null) NONE.add(id);
        else OPEN.put(id, o);
        return o;
    }

    private static void save(UUID id, @Nullable Order o) {
        if (o == null) {
            OPEN.remove(id);
            NONE.add(id);
            Ledger.note(id, ORDER, "");
        } else {
            NONE.remove(id);
            OPEN.put(id, o);
            Ledger.note(id, ORDER, o.encode());
        }
    }

    /**
     * [Wagons.build] The town wants this wagon: if nothing is on the bench yet and the stores hold every one of its
     * makings, they are set by for it now, out of the stores, and the order opened. True if it was.
     */
    static boolean open(ServerLevel level, Villages.Village v, WagonKind k, long day) {
        UUID id = v.id();
        if (order(id) != null) return false;
        String short_ = shortOf(level, id, k);
        if (short_ != null) {
            status(id, "the stores are short of " + short_ + " for a " + k.label);
            return false;
        }
        List<ItemStack> held = new ArrayList<>();
        for (WagonKind.Making m : k.makings) {
            List<ItemStack> got = Services.take(level, id, m.what(), m.count());
            held.addAll(got);
            if (total(got) < m.count()) {                                    // the stores changed under it: all of it back
                for (ItemStack s : held) Crafts.store(level, v, s);
                return false;
            }
        }
        Order o = new Order(k, 0, work(k), 0, day, day, "", merge(held));
        save(id, o);
        STATUS.remove(id);
        forget(id);                                                          // the post stands from now: its holder reckoned afresh
        VillageFolkEntity h = holder(id);
        Villages.tell(id, day, (h == null ? "the makings of a " + k.label + " were set by for the wheelwright"
            : h.displayNameCap() + " the wheelwright set to work on a " + k.label + " for the trade road") + ": " + k.makingsWords());
        LOG.info("[MCA-WHEEL] {} opened an order for a {} ({}s of work); makings held: {}", Villages.name(id), k.label, o.need, o.encode());
        return true;
    }

    /** What the stores are short of for this wagon ("6 wool for the canvas"), or null if they hold it all. */
    @Nullable
    static String shortOf(ServerLevel level, UUID id, WagonKind k) {
        List<String> out = new ArrayList<>();
        for (WagonKind.Making m : k.makings) {
            int have = Market.stock(level, id, m.what());
            if (have < m.count()) out.add((m.count() - have) + " " + m.words());
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    private static int total(List<ItemStack> l) {
        int n = 0;
        for (ItemStack s : l) n += s.getCount();
        return n;
    }

    /** Like stacks of the same thing put together, so the books keep a short list. */
    private static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            if (s.isEmpty()) continue;
            ItemStack same = null;
            for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, s)) { same = o; break; }
            if (same != null) same.grow(s.getCount());
            else out.add(s.copy());
        }
        return out;
    }

    // ------------------------------------------------------------------ who

    /** Does the town keep a wheelwright: a wagon of its own, or one on the bench? */
    public static boolean stands(@Nullable UUID id) {
        return id != null && Villages.get(id) != null && (Wagons.owned(id) != null || order(id) != null);
    }

    /** Free for the post: grown, of the town, not the elder, nor a hand whose day is the watch's or out of the town. */
    static boolean fit(VillageFolkEntity f, UUID id) {
        if (!f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired() || f.isElder() || !id.equals(f.ownerId())) return false;
        if (f.expedition() != null || f.ageYears() < 18) return false;
        StationTask t = f.stationTask();
        return t != StationTask.GUARD && t != StationTask.NETHER && t != StationTask.CAVE && t != StationTask.SCOUT
            && t != StationTask.EMERALD && t != StationTask.FERRY && t != StationTask.DIVER && t != StationTask.HEAL;
    }

    /** How well suited: a hand at the shop's bench, wood and iron in its hands, a hard worker. */
    static int score(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        int s = f.tradeLevel(StationTask.WOOD) + f.tradeLevel(StationTask.SMITH) + f.tradeLevel(StationTask.SHOP) / 2;
        if (Workshop.isHand(f)) s += 30;
        if (t == StationTask.WOOD || t == StationTask.SHOP || t == StationTask.SMITH) s += 15;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 10;
        if (f.life().has(Social.Trait.CURIOUS)) s += 5;
        if (f.ageYears() >= 25 && f.ageYears() <= 55) s += 5;
        return s;
    }

    /** The town's wheelwright: the interview's choice while it is fit, else the best suited; null where none is kept. */
    @Nullable
    public static VillageFolkEntity holder(@Nullable UUID id) {
        if (id == null) return null;
        Object[] was = HOLDER.get(id);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0L : server.getTickCount();
        if (was != null && now - (Long) was[0] < 100L && now >= (Long) was[0]) {
            VillageFolkEntity f = (VillageFolkEntity) was[1];
            if (f == null || f.isAlive() && fit(f, id)) return f;
        }
        VillageFolkEntity got = reckon(id);
        HOLDER.put(id, new Object[]{ now, got });
        return got;
    }

    @Nullable
    private static VillageFolkEntity reckon(UUID id) {
        if (!stands(id)) return null;
        VillageFolkEntity chosen = Interviews.holder(id, POST);
        if (chosen != null && fit(chosen, id)) return chosen;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !fit(f, id)) continue;
            int s = score(f);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** The town's wheelwright looked up afresh at the next ask (a new one chosen at interview). */
    static void forget(UUID id) {
        HOLDER.remove(id);
    }

    /** Is this folk its town's wheelwright? */
    public static boolean is(VillageFolkEntity f) {
        return f.ownerId() != null && f == holder(f.ownerId());
    }

    // ------------------------------------------------------------------ the second's round

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 13) return;
        com.jrpetty.mcassistant.Guard.run("wheelwright", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    step(level, v);
                }
            }
        });
    }

    /** A town's second: its order worked at (or finished, or given up), or its battered wagon mended. */
    static void step(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Order o = order(id);
        long day = level.getDayTime() / 24000L;
        if (o != null) {
            if (o.done >= o.need) { finish(level, v, o); return; }
            if (day - o.last > GIVE_UP_DAYS) { giveUp(level, v, o, day); return; }
        }
        TradeWagon w = o == null ? battered(level, id) : null;
        if (o == null && w == null) return;
        long t = level.getDayTime() % 24000L;
        if (t < DAY_FROM || t >= DAY_TILL || Raids.underAlarm(id) || Weather.stormy(level)) return;
        VillageFolkEntity h = holder(id);
        if (h == null) {
            status(id, "no wheelwright to work at it");
            return;
        }
        if (o != null) work(level, v, o, h, day);
        else mend(level, v, w, h);
    }

    private static void work(ServerLevel level, Villages.Village v, Order o, VillageFolkEntity h, long day) {
        UUID id = v.id();
        BlockPos spot = bench(level, v);
        if (spot == null || PostStints.stuck(h, STUCK)) spot = yard(level, v);
        if (!PostStints.call(h, POST, spot, (o.done == 0 ? "starting" : "building") + " the town's " + o.kind.label, STINT, REST)) return;
        STATUS.remove(id);
        int sec = PostStints.worked(h);
        if (sec == 1) {
            o.visits++;
            String was = o.by;
            o.by = h.displayNameCap();
            save(id, o);
            if (o.done > 0 && !was.isEmpty() && !was.equals(o.by)) {
                Villages.tell(id, day, o.by + " the wheelwright took up the half-built " + o.kind.label + " where " + was + " left it");
            }
            FolkTalk.speak(h, o.done == 0 ? FolkTalk.pick(h.getRandom(), "Right: a " + o.kind.label + ". Wheels first.",
                    "The makings are all here. Let's begin her.")
                : FolkTalk.pick(h.getRandom(), "Back to the " + o.kind.label + ".", "Another day on her. The spokes next.",
                    "She's coming on."));
        }
        o.done++;
        o.last = day;
        if (sec % 3 == 0) level.playSound(null, h.blockPosition(), SoundEvents.AXE_STRIP, SoundSource.NEUTRAL, 0.5F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        if (o.kind.drawn && sec % 12 == 6) level.playSound(null, h.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.25F, 1.2F);
        if (o.done % SAVE_EVERY == 0) save(id, o);
        if (o.done >= o.need) {
            save(id, o);
            PostStints.end(h, REST);
            finish(level, v, o);
        }
    }

    /** The order's work done: the new wagon into the yard (the old one, home, broken up for its planks). */
    private static void finish(ServerLevel level, Villages.Village v, Order o) {
        UUID id = v.id();
        WagonKind had = Wagons.owned(id);
        TradeWagon w = Wagons.replace(level, v, o.kind);
        if (w == null) {
            status(id, "the " + o.kind.label + " is built, and waits for the old " + (had == null ? "wagon" : had.label) + " to come home");
            return;
        }
        save(id, null);
        STATUS.remove(id);
        Carters.forget(id);                                                  // a wagon to drive: the carter reckoned afresh
        int[] b = book(id);
        b[0]++;
        book(id, b);
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity h = holder(id);
        String by = h != null ? h.displayNameCap() : o.by.isEmpty() ? null : o.by;
        Villages.tell(id, day, (by == null ? "the wheelwright" : by + " the wheelwright") + " built the town " + (had == null ? "its first " : "a ")
            + o.kind.label + " for the trade road, over " + o.visits + (o.visits == 1 ? " visit" : " visits") + " to the bench"
            + (had == null ? "" : ", and broke up the old " + had.label + " for its planks"));
        if (h != null) FolkTalk.speak(h, FolkTalk.pick(h.getRandom(), "There she stands. The best " + o.kind.label + " on the road!",
            "Done! Mind the paint, it's still wet.", "Finished. She'll carry the town's trade for years."));
        LOG.info("[MCA-WHEEL] {} finished its {} after {}s over {} visits", Villages.name(id), o.kind.label, o.done, o.visits);
    }

    /** An order nobody has worked at for ten days: given up, its makings back to the stores. */
    private static void giveUp(ServerLevel level, Villages.Village v, Order o, long day) {
        for (ItemStack s : o.makings) Crafts.store(level, v, s.copy());
        save(v.id(), null);
        Villages.tell(v.id(), day, "the half-built " + o.kind.label + " was given up for want of a wheelwright; its makings went back to the stores");
        LOG.info("[MCA-WHEEL] {} gave up its {} order after {} idle days; makings returned: {}", Villages.name(v.id()), o.kind.label,
            day - o.last, o.encode());
    }

    // ------------------------------------------------------------------ mending

    /** The town's wagon home in its yard, battered below nine tenths of its strength (or still being mended), or null. */
    @Nullable
    static TradeWagon battered(ServerLevel level, UUID id) {
        TradeWagon w = Wagons.wagonOf(level, id);
        if (w == null || w.phase != TradeWagon.Phase.PARKED || w.held || w.driver != null) return null;
        boolean begun = MENDING.containsKey(id);                            // a mending begun is seen through to the end
        return w.hp < w.kind().health * MEND_BELOW || begun && w.hp < w.kind().health ? w : null;
    }

    private static void mend(ServerLevel level, Villages.Village v, TradeWagon w, VillageFolkEntity h) {
        UUID id = v.id();
        if (!PostStints.call(h, POST, side(level, w), "mending the town's " + w.kind().label, STINT, REST)) return;
        int sec = PostStints.worked(h);
        if (sec == 1) FolkTalk.speak(h, FolkTalk.pick(h.getRandom(), "She took a knock or two on the road. Let's see to her.",
            "A cracked felloe, a loose board. Nothing a few planks won't put right."));
        if (sec % MEND_STEP != 0) {
            if (sec % 3 == 0) level.playSound(null, h.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.5F, 0.9F);
            return;
        }
        boolean iron = w.kind().drawn && (sec / MEND_STEP) % IRON_EVERY == 0;
        if (Market.stock(level, id, PLANKS) < MEND_PLANKS || iron && Market.stock(level, id, IRON) < 1) {
            status(id, "the stores have no " + (Market.stock(level, id, PLANKS) < MEND_PLANKS ? "planks" : "iron") + " to mend the wagon with");
            PostStints.end(h, REST);
            return;
        }
        if (!Crafts.take(level, v, PLANKS, MEND_PLANKS)) return;
        if (iron && !Crafts.take(level, v, IRON, 1)) iron = false;
        int[] used = MENDING.computeIfAbsent(id, k -> new int[2]);
        used[0] += MEND_PLANKS;
        if (iron) {
            used[1]++;
            level.playSound(null, h.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.3F, 1.3F);
        }
        w.hp = Math.min(w.kind().health, w.hp + MEND_HP);
        STATUS.remove(id);
        if (w.hp < w.kind().health) return;
        PostStints.end(h, REST);
        MENDING.remove(id);
        int[] b = book(id);
        b[1]++;
        b[2] += used[0];
        b[3] += used[1];
        book(id, b);
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, h.displayNameCap() + " the wheelwright mended the town's " + w.kind().label + ", with " + used[0] + " planks"
            + (used[1] > 0 ? " and " + used[1] + " iron" : "") + " from the stores");
        FolkTalk.speak(h, "Good as new. She's fit for the road.");
        LOG.info("[MCA-WHEEL] {} mended its {} ({} planks, {} iron)", Villages.name(id), w.kind().label, used[0], used[1]);
    }

    // ------------------------------------------------------------------ the wheelwright's hand (a player's paid shift)

    /** [ShiftTasks.post] The yard, with work in it when the town's wagon is home and battered; null with no wagon. */
    @Nullable
    static ShiftTasks.Post shiftPost(ServerLevel level, Villages.Village v) {
        if (Wagons.owned(v.id()) == null) return null;
        TradeWagon w = battered(level, v.id());
        BlockPos at = w != null ? w.blockPosition() : Wagons.park(level, v).pos();
        return new ShiftTasks.Post(at, null, w != null, "The town's wagon is sound, or out on the road.");
    }

    /** [ShiftTasks.next] The battered wagon marked, if nobody else's task is on it. */
    @Nullable
    static Shifts.Task shiftTask(ServerLevel level, net.minecraft.server.level.ServerPlayer p, Shifts.Shift s, Villages.Village v, long now) {
        TradeWagon w = battered(level, v.id());
        if (w == null) return null;
        for (Shifts.Shift o : Shifts.ON.values()) if (o != s && o.role == ShiftRole.WAGON && o.task != null && o.task.entity == w.getId()) return null;
        int steps = (int) Math.ceil((w.kind().health - w.hp) / MEND_HP);
        Shifts.Task t = new Shifts.Task("mended", "Mend the marked " + w.kind().label + ": right-click it holding planks, two a step ("
            + steps + (steps == 1 ? " step" : " steps") + " to go).", w.blockPosition(), now,
            (long) (Math.sqrt(p.blockPosition().distSqr(w.blockPosition())) / ShiftTasks.WALK * 20) + 60L * steps);
        t.entity = w.getId();
        t.marks.add(w.blockPosition());
        return t;
    }

    /**
     * [ShiftTasks.watch] The wagon driven off, or mended whole meanwhile by the wheelwright, before the task is done: no
     * fault, and the steps the player did paid for.
     */
    static void shiftWatch(ServerLevel level, net.minecraft.server.level.ServerPlayer p, Shifts.Shift s, Shifts.Task t) {
        if (level.getEntity(t.entity) instanceof TradeWagon w && w.isAlive() && w.phase == TradeWagon.Phase.PARKED && w.hp < w.kind().health) return;
        if (t.laid > 0) Shifts.done(level, p, s, t, 0, t.laid, "mending the town's wagon, finished by other hands", null);
        else s.task = null;
    }

    /**
     * [TradeWagon.interact] A player on the wheelwright's-hand shift right-clicking its marked wagon with planks in hand:
     * two of its planks mend the wagon a step; mended whole, the task is done and paid by the steps. True if it was this
     * (the manifest is not shown then).
     */
    static boolean mendByHand(ServerLevel level, TradeWagon w, net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer p)) return false;
        Shifts.Shift s = Shifts.ON.get(p.getUUID());
        if (s == null || s.role != ShiftRole.WAGON || s.task == null || s.task.entity != w.getId()) return false;
        ItemStack held = p.getMainHandItem();
        if (!held.is(ItemTags.PLANKS) || held.getCount() < MEND_PLANKS) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("Hold two planks or more to mend the wagon."), true);
            return true;
        }
        if (!p.getAbilities().instabuild) held.shrink(MEND_PLANKS);
        w.hp = Math.min(w.kind().health, w.hp + MEND_HP);
        s.task.laid++;
        level.playSound(null, w.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.8F, 0.9F);
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        if (w.hp >= w.kind().health) {
            int steps = s.task.laid;
            Shifts.done(level, p, s, s.task, 0, steps, "mending the town's " + w.kind().label, null);
            Villages.tell(s.village, level.getDayTime() / 24000L, p.getName().getString() + " mended the town's " + w.kind().label
                + " as the wheelwright's hand, with " + steps * MEND_PLANKS + " planks of its own");
        } else {
            int left = (int) Math.ceil((w.kind().health - w.hp) / MEND_HP);
            s.task.text = "Mend the marked " + w.kind().label + ": " + left + (left == 1 ? " step" : " steps") + " to go.";
        }
        return true;
    }

    // ------------------------------------------------------------------ where

    /** Where the wheelwright stands to mend the wagon: at its side, on the wagon's own level, where there is room. */
    static BlockPos side(ServerLevel level, TradeWagon w) {
        for (double right : new double[]{ 1.8, -1.8 }) {
            net.minecraft.world.phys.Vec3 s = w.spot(level, 0.0, right);
            BlockPos p = BlockPos.containing(s.x, w.getY(), s.z);
            if (!level.getBlockState(p).blocksMotion() && !level.getBlockState(p.above()).blocksMotion()) return p;
        }
        return w.blockPosition();
    }

    /** The bench of the town's shed (the crafting table of its drawing), or null with no shed (or its ground not in). */
    @Nullable
    static BlockPos bench(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Villages.builtStructure(v.id(), SHED);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        BlockPos[] kept = BENCH.get(v.id());
        if (kept != null && kept[0].equals(b.anchor())) return kept[1];
        BlockPos got = null;
        for (BuildGoal.Placement p : BuildGoal.plan(SHED, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CRAFTING_TABLE) { got = p.pos().immutable(); break; }
        }
        if (got == null) return null;
        BENCH.put(v.id(), new BlockPos[]{ b.anchor(), got });
        return got;
    }

    /** By the yard where the wagon stands: where the wheelwright works while the town has no shed. */
    static BlockPos yard(ServerLevel level, Villages.Village v) {
        return Wagons.park(level, v).pos().offset(3, 0, 0);
    }

    /** Is a shed wanted: a town of ten with a wagon (or one on the bench), and no shed yet? */
    static boolean shedWanted(UUID id) {
        return Villages.headcount(id) >= SHED_FROM_FOLK && Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal()
            && Villages.builtAt(id, SHED) == null && (Wagons.owned(id) != null || order(id) != null);
    }

    static String why() {
        return "a wheelwright's shed by the yard: a bench, a grindstone and an anvil, where the town's wagons are built and mended";
    }

    // ------------------------------------------------------------------ the books

    private static int[] book(UUID id) {
        int[] out = new int[4];
        String s = Ledger.note(id, BOOK);
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split("\\|");
        for (int i = 0; i < Math.min(4, p.length); i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException ignored) {
                // a hand-edited book: that count from nought
            }
        }
        return out;
    }

    private static void book(UUID id, int[] b) {
        Ledger.note(id, BOOK, b[0] + "|" + b[1] + "|" + b[2] + "|" + b[3]);
    }

    private static void status(UUID id, String why) {
        String was = STATUS.put(id, why);
        if (!why.equals(was)) LOG.info("[MCA-WHEEL] {}: {}", Villages.name(id), why);
    }

    /** What holds the wheelwright up just now, or "". */
    static String status(UUID id) {
        return STATUS.getOrDefault(id, "");
    }

    /** The order in words: "a covered wagon, 300 of 450 (3 visits)". */
    static String orderWords(Order o) {
        return "a " + o.kind.label + ", " + o.done + " of " + o.need + " seconds' work done over " + o.visits + (o.visits == 1 ? " visit" : " visits");
    }

    /** "/village roads": the wheelwright, its order, its shed, its wagon, its record. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        VillageFolkEntity h = holder(id);
        Order o = order(id);
        WagonKind k = Wagons.owned(id);
        TradeWagon w = Wagons.wagonOf(level, id);
        out.add("Wheelwright: " + (h == null ? (stands(id) ? "none yet" : "not kept (no wagon, none on the bench)") : h.displayNameCap()
            + " (" + h.stationTask().label + ")") + "; shed: " + (Villages.builtAt(id, SHED) != null ? "built" : shedWanted(id) ? "wanted" : "none"));
        if (o != null) out.add("  On the bench: " + orderWords(o) + "; makings held: " + o.encode().substring(o.encode().lastIndexOf('|') + 1));
        if (k != null) out.add("  The town's " + k.label + ": " + (w == null ? "out of sight" : Math.round(w.hp) + " of " + Math.round(k.health)
            + (w.hp < k.health * MEND_BELOW ? ", to be mended" : ", sound")));
        String s = status(id);
        if (!s.isEmpty()) out.add("  Waiting: " + s);
        int[] b = book(id);
        if (b[0] + b[1] > 0) out.add("  Built " + b[0] + ", mended " + b[1] + " (" + b[2] + " planks, " + b[3] + " iron)");
        return out;
    }

    /** Its card (FolkTalk.card): the wheelwright's post and what is on its bench. */
    static String cardLine(VillageFolkEntity f) {
        if (!is(f)) return "";
        Order o = order(f.ownerId());
        return "the town's wheelwright" + (o == null ? "" : "; on the bench: " + orderWords(o));
    }

    // ------------------------------------------------------------------ the talk

    /** "How's the wagon coming on?": the order, the wagon's state, or what the stores are short of for the next. */
    static String talk(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return "I've no wagon to speak of.";
        VillageFolkEntity h = holder(id);
        if (h != f) {
            return h == null ? "We've no wheelwright. No wagon either, come to that."
                : "Ask " + h.displayNameCap() + ": the wagon's its work. " + glance(level, v);
        }
        Order o = order(id);
        if (o != null) {
            int left = Math.max(0, o.need - o.done);
            String when = left <= 60 ? "She'll be done today." : left <= 200 ? "Another visit or two to the bench." : "A few more visits yet.";
            return "The " + o.kind.label + " is coming on: " + (o.done * 100 / Math.max(1, o.need)) + "% done, over " + o.visits
                + (o.visits == 1 ? " visit" : " visits") + " so far. I've all its makings set by: " + makingsWords(o) + ". " + when
                + (status(id).isEmpty() ? "" : " Just now " + status(id) + ".");
        }
        return glance(level, v);
    }

    /** The town's wagon in a line: sound, battered, or not yet (and what it waits for). */
    private static String glance(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        WagonKind have = Wagons.owned(id);
        TradeWagon w = Wagons.wagonOf(level, id);
        WagonKind want = Wagons.buildable(v);
        String next = have != null && have.ordinal() >= want.ordinal() ? null : shortOf(level, id, want);
        if (have == null) {
            if (!Wagons.trades(v)) return "The town has no trade road yet, so there's no wagon to build.";
            return next == null ? "We're to have a " + want.label + ": the makings are in the stores, and the work starts soon."
                : "We're to have a " + want.label + ", but the stores are short of " + next + ".";
        }
        String state = w == null ? "The " + have.label + " is out on the road."
            : w.hp < have.health * MEND_BELOW ? "The " + have.label + " took a battering (" + Math.round(w.hp) + " of " + Math.round(have.health)
                + "): it wants planks" + (have.drawn ? " and a little iron" : "") + ", and a morning's mending."
            : "The " + have.label + " is sound.";
        if (next != null) state += " The town's grown into a " + want.label + "; we're short of " + next + " for it.";
        return state;
    }

    private static String makingsWords(Order o) {
        List<String> parts = new ArrayList<>();
        for (ItemStack s : o.makings) parts.add(s.getCount() + " " + OutdoorTalk.word(s.getItem()));
        return String.join(", ", parts);
    }

    /** Typed words that ask the wheelwright about the wagon. */
    static boolean meant(String lower) {
        return lower.contains("wagon") && (lower.contains("coming") || lower.contains("how's") || lower.contains("how is")
            || lower.contains("built") || lower.contains("mend") || lower.contains("repair"));
    }

    // ------------------------------------------------------------------ for the tests

    /** Tests: the order opened now as Wagons.build would open it (false if one is open or the makings are short). */
    public static boolean openForTests(ServerLevel level, Villages.Village v, WagonKind k) {
        return open(level, v, k, level.getDayTime() / 24000L);
    }

    /** Tests: the order in words, or "" with none. */
    public static String orderForTests(UUID id) {
        Order o = order(id);
        return o == null ? "" : o.kind.name() + " " + o.done + "/" + o.need + " visits " + o.visits + " by " + o.by;
    }

    /** Tests: how many of this the order holds as its makings. */
    public static int heldForTests(UUID id, Item it) {
        Order o = order(id);
        return o == null ? 0 : o.count(it);
    }

    /** Tests: the order's work pushed on by so many seconds (as if the wheelwright had stood at the bench), its books kept. */
    public static void workForTests(UUID id, int seconds, long day) {
        Order o = order(id);
        if (o == null) return;
        o.done = Math.min(o.need, o.done + seconds);
        o.visits++;
        o.last = day;
        save(id, o);
    }

    /** Tests: the cache forgotten, so the order is read again from the books (as after a restart). */
    public static void forgetForTests(UUID id) {
        OPEN.remove(id);
        NONE.remove(id);
        HOLDER.remove(id);
        BENCH.remove(id);
    }

    /** Tests: a town's second, now. */
    public static void stepForTests(ServerLevel level, Villages.Village v) {
        step(level, v);
    }

    /** Tests: the wagon's strength, and set it (a battering). */
    public static float hpForTests(TradeWagon w) { return w.hp; }
    public static void batterForTests(TradeWagon w, float hp) { w.hp = hp; }
    public static String statusForTests(UUID id) { return status(id); }

    /** Tests: where the wheelwright is and what holds it, in a line (for a test's log). */
    public static String debugForTests(ServerLevel level, UUID id) {
        VillageFolkEntity h = holder(id);
        TradeWagon w = battered(level, id);
        return "holder " + (h == null ? "none" : h.displayNameCap() + " at " + h.blockPosition().toShortString() + " '" + h.offWorkWhy()
            + "' fit " + TownJobs.fit(h, POST) + " stint '" + PostStints.doing(h) + "'") + "; battered " + (w == null ? "none"
            : w.blockPosition().toShortString() + " side " + side(level, w).toShortString());
    }
    public static String talkForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level ? talk(f, level) : "";
    }

    /** Tests: every holder's rest from its post's work let go (the next call starts a stint at once). */
    public static void restedForTests() {
        PostStints.resetForTests();
    }

    /** Tests: a right-click on the wagon by a player, as TradeWagon.interact has it: true if it was a shift's mending. */
    public static boolean mendByHandForTests(ServerLevel level, TradeWagon w, net.minecraft.server.level.ServerPlayer p) {
        return mendByHand(level, w, p);
    }
}
