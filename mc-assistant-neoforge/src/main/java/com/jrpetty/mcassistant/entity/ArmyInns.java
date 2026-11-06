package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [realm-polish] An army on a long march rests at a roadside inn (Roadside) on its way.
 *
 * <ul>
 * <li><b>The way.</b> When a column is given its road (Campaigns.route, sendHome) and the road is a long one, an inn
 *     within a bowshot of it, not at either end, is put on its way: the column turns in along the road by the inn's door.
 *     Only an inn it may use: open, kept, and neither of its towns at war with the army's; or, at war with the town that
 *     built it, one it may plunder.</li>
 * <li><b>A meal, or the night.</b> By day the column halts half a minute for a hot meal; reaching the inn after dusk
 *     it stops for the night and marches on at dawn. The men sit in rows along the road before the door; the officers (the
 *     Captain, the standard-bearer, the sergeants) take the inn's rooms and sleep in its beds. Each man eats one dish out of
 *     the larder; the town's treasury pays the keeper at the inn's own prices (a meal two coins, a room three): real coin,
 *     into the keeper's till (left on the bar for it if it is not about), half of it the inn's town's rent, which an army of
 *     the town that built it carries home itself. A fed army is in better heart, and its wounded a little mended.</li>
 * <li><b>The enemy.</b> An army at war with the town that built the inn is not served. It requisitions the larder: what
 *     its men can eat they eat, the rest goes on its carts and home into its stores. The keeper shouts at it, the inn's
 *     town hears of it and holds it against the army's town; the keeper bars the door to that town's armies for a fortnight
 *     after.</li>
 * <li><b>The battle's timing.</b> A halt never makes a battle at night: the fields are fought when both armies are at
 *     their camps, by the same clock as ever (an army that ate late camps the night and fights at dawn). An army that gets
 *     to its camp while the other rests the night at an inn makes camp round its fire; one home while the other is still
 *     on the road is stood down. A march home is given the halt's length on top of its usual time.</li>
 * </ul>
 * The chronicles and the war books of both towns tell it; the inn's books (the Roads page, the keeper's card) keep who
 * stayed. A restart in the middle of a halt marches on (each army rests at an inn once a leg, written in its town's notes).
 * Cost: a look over the inns when a column is given its road; nothing at all while no army is near an inn.
 */
public final class ArmyInns {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private ArmyInns() {}

    /** A march at least this long passes inns it may stop at. */
    static final int LONG_MARCH = 120;
    /** An inn this near the column's road (blocks off it) is on its way; never one this near either end of it. */
    static final int PASS = 32, CLEAR = 20;
    /** A meal: half a minute. A requisition: a quarter of a minute. */
    static final long MEAL_TICKS = 600L, PLUNDER_TICKS = 300L;
    /** After dusk it stops for the night; it marches on at this hour of the morning. */
    static final long NIGHT_FROM = 12000L, MARCH_ON = 300L;
    /** The keeper bars its door to a town whose army plundered it for so many days. */
    static final int BARRED_DAYS = 14;
    /** Men in a row along the road, and how far apart. */
    static final int PER_ROW = 9;
    /** An army in better heart for a hot meal, and for a night under a roof; never past this. */
    static final int MEAL_HEART = 2, NIGHT_HEART = 4, HEART_MOST = 80;

    static final String STAYED = "rp.stayed", BOOK = "rp.innbook.", TILL = "rp.innbar.", RENT = "rp.innrent", BARRED = "rp.innbarred/";

    enum Kind { STAY, PLUNDER }

    /** An inn put on an army's way: which, where its road point is, how far along the way. */
    static final class Plan {
        final UUID owner, other;
        final BlockPos at;
        final double along;
        final String leg;

        Plan(UUID owner, UUID other, BlockPos at, double along, String leg) {
            this.owner = owner;
            this.other = other;
            this.at = at;
            this.along = along;
            this.leg = leg;
        }
    }

    /**
     * An army halted at an inn: which, why, till when (by the clock for a meal; by the day's own time for the night, so a
     * night slept through by the players is over at their dawn too), who has a room.
     */
    static final class Halt {
        final UUID owner, other, town;
        final Kind kind;
        final boolean night;
        final long until, morning;
        final Campaigns.Stage stage;
        final Map<UUID, BlockPos> rooms = new LinkedHashMap<>();
        int fed, carried, paid;
        boolean otherCamped;

        Halt(UUID owner, UUID other, UUID town, Kind kind, boolean night, long until, long morning, Campaigns.Stage stage) {
            this.owner = owner;
            this.other = other;
            this.town = town;
            this.kind = kind;
            this.night = night;
            this.until = until;
            this.morning = morning;
            this.stage = stage;
        }

        boolean over(ServerLevel level) {
            return level.getGameTime() >= until || morning >= 0 && level.getDayTime() >= morning;
        }
    }

    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<String, Halt> HALTS = new ConcurrentHashMap<>();
    /** Officers abed at an inn tonight: the bed's head, by folk (what each fighter's step looks up). */
    private static final Map<UUID, BlockPos> ROOMS = new ConcurrentHashMap<>();
    private static volatile boolean on = true;

    public static void resetForTests() {
        PLANS.clear();
        HALTS.clear();
        ROOMS.clear();
        on = true;
    }

    /** Tests of the war itself: no inns on the armies' ways (true again at the next reset). */
    public static void switchForTests(boolean yes) {
        on = yes;
    }

    static String key(Campaigns.Campaign c, int i) {
        return Ledger.pair(c.a, c.b) + "#" + i;
    }

    // ------------------------------------------------------------------ the way

    /** The inns built and standing (open, or shut by a war or for want of a keeper), each once. */
    static List<Roadside.House> inns() {
        List<Roadside.House> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Villages.Village v : Villages.every()) {
            for (RoadWorks.Road r : RoadWorks.roads(v.id())) {
                String k = RoadWorks.key(r.a(), r.b());
                if (!seen.add(k)) continue;
                Roadside.House h = Roadside.of(r.a(), r.b());
                if (h != null && "open".equals(h.state)) out.add(h);
            }
        }
        return out;
    }

    /** What this town's army may do at this inn now: be served, plunder it, or nothing (null). */
    @Nullable
    static Kind kind(UUID town, Roadside.House h, long day) {
        if (Wars.atWar(town, h.owner)) return Kind.PLUNDER;
        if (Wars.atWar(town, h.other) || !Roadside.open(h)) return null;
        long barred = WarBooks.num(Ledger.note(h.owner, BARRED + town), -100);
        return day - barred < BARRED_DAYS ? null : Kind.STAY;
    }

    /** The point of the inn's road nearest its door: where the column turns in. */
    @Nullable
    static BlockPos roadPoint(Roadside.House h) {
        RoadWorks.Road r = RoadWorks.road(Villages.get(h.owner), Villages.get(h.other));
        if (r == null) return null;
        int[] best = null;
        long bd = Long.MAX_VALUE;
        for (int[] p : r.line()) {
            long dx = p[0] - h.anchor.getX(), dz = p[1] - h.anchor.getZ(), d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best == null ? null : new BlockPos(best[0], h.anchor.getY(), best[1]);
    }

    /**
     * [Campaigns.route, sendHome] Army i has been given its road: if it is a long one and passes an inn the army may use
     * (or plunder), the inn's road point is put on its way, the nearest such inn along it, and the column will halt there.
     */
    static void plan(Campaigns.Campaign c, int i) {
        String key = key(c, i);
        PLANS.remove(key);
        Armies.Army a = c.armies[i];
        List<BlockPos> way = c.ways.get(i);
        if (!on || c.staged >= 0 || a == null || way.size() < 2) return;
        double len = Campaigns.length(way);
        if (len < LONG_MARCH) return;
        Villages.Village home = Villages.get(a.town);
        if (home == null) return;
        long day = Bonds.today();
        String leg = Long.toString(way.get(way.size() - 1).asLong());
        Plan best = null;
        int bestSeg = -1;
        for (Roadside.House h : inns()) {
            Villages.Village o = Villages.get(h.owner);
            if (o == null || !o.dim().equals(home.dim()) || kind(a.town, h, day) == null) continue;
            if (stayed(a.town, c.day, leg, RoadWorks.key(h.owner, h.other))) continue;
            BlockPos at = roadPoint(h);
            if (at == null) continue;
            double before = 0;
            for (int k = 1; k < way.size(); k++) {
                BlockPos p = way.get(k - 1), q = way.get(k);
                double sx = q.getX() - p.getX(), sz = q.getZ() - p.getZ(), seg = Math.sqrt(sx * sx + sz * sz);
                if (seg > 0.01) {
                    double t = ((at.getX() - p.getX()) * sx + (at.getZ() - p.getZ()) * sz) / (seg * seg);
                    if (t >= 0 && t <= 1) {
                        double ox = p.getX() + sx * t - at.getX(), oz = p.getZ() + sz * t - at.getZ();
                        double off = Math.sqrt(ox * ox + oz * oz), s = before + seg * t;
                        if (off <= PASS && s >= CLEAR && s <= len - CLEAR && (best == null || s < best.along)) {
                            // How far along it the inn is once the way turns in to it.
                            double in = before + Math.sqrt(Campaigns.flat(p, at));
                            best = new Plan(h.owner, h.other, at, in, leg);
                            bestSeg = k;
                        }
                    }
                }
                before += seg;
            }
        }
        if (best == null) return;
        List<BlockPos> turned = new ArrayList<>(way);
        turned.add(bestSeg, best.at);
        c.ways.set(i, turned);
        PLANS.put(key, best);
        LOG.info("[MCA-WAR] {}'s army will pass the inn on the road between {} and {} ({} blocks along its march of {})",
            Villages.name(a.town), Villages.name(best.owner), Villages.name(best.other), (int) best.along, (int) Campaigns.length(turned));
    }

    static boolean stayed(UUID town, long campaignDay, String leg, String inn) {
        String s = Ledger.note(town, STAYED);
        return s != null && s.equals(campaignDay + "@" + leg + "@" + inn);
    }

    // ------------------------------------------------------------------ the march's second

    /**
     * [Campaigns.marching, homeward] A second of army i's march: true while it is halted at an inn (or, its own march done,
     * waits in camp, or is stood down at home, while the other army rests at an inn for the night), so it is not marched
     * on this second. Arriving at its inn's road point, it halts.
     */
    static boolean halted(ServerLevel level, Campaigns.Campaign c, int i) {
        if (PLANS.isEmpty() && HALTS.isEmpty()) return false;
        String key = key(c, i);
        Halt h = HALTS.get(key);
        if (h != null) {
            if (h.stage != c.stage) {
                finish(level, c, i, h, false);
                return false;
            }
            if (h.over(level)) {
                finish(level, c, i, h, true);
                return false;
            }
            tend(level, c, i, h);
            return true;
        }
        if (waiting(level, c, i)) return true;
        Plan p = PLANS.get(key);
        if (p == null || c.along[i] < p.along - 0.01) return false;
        PLANS.remove(key);
        c.along[i] = p.along;
        return arrive(level, c, i, p);
    }

    /** Army i's own march is done while the other rests the night at an inn: camped round its fire, or stood down at home. */
    static boolean waiting(ServerLevel level, Campaigns.Campaign c, int i) {
        Halt other = HALTS.get(key(c, 1 - i));
        Armies.Army a = c.armies[i];
        if (other == null || a == null || c.ways.get(i).isEmpty() || c.along[i] < Campaigns.length(c.ways.get(i))) return false;
        if (c.stage == Campaigns.Stage.HOME) {
            Campaigns.standDown(level, c, i);
            return true;
        }
        if (!other.night || c.stage != Campaigns.Stage.MARCH) return false;
        if (!other.otherCamped) {
            other.otherCamped = true;
            List<BlockPos> way = c.ways.get(i);
            BlockPos at = way.get(way.size() - 1);
            double fx = i == 0 ? c.ground.ax() : -c.ground.ax(), fz = i == 0 ? c.ground.az() : -c.ground.az();
            if (Math.abs(fx) + Math.abs(fz) < 0.01) { double[] hd = Campaigns.heading(way, Campaigns.length(way)); fx = hd[0]; fz = hd[1]; }
            Battles.camp(level, a, at, fx, fz, null);
            Villages.tell(a.town, Bonds.today(), "our army reached its ground and made camp round its fire: " + Villages.name(a.enemy)
                + "'s army rests the night at an inn on its way");
        }
        return true;
    }

    /** At the inn's road point: served, or the larder taken; and the column drawn up by the door. False if it marches on past. */
    static boolean arrive(ServerLevel level, Campaigns.Campaign c, int i, Plan p) {
        Armies.Army a = c.armies[i];
        Roadside.House inn = Roadside.of(p.owner, p.other);
        long now = level.getGameTime(), dt = level.getDayTime(), day = dt / 24000L, tod = dt % 24000L;
        String innKey = RoadWorks.key(p.owner, p.other);
        Ledger.note(a.town, STAYED, c.day + "@" + p.leg + "@" + innKey);     // once a leg, whatever comes of it
        Kind kind = inn == null || !"open".equals(inn.state) ? null : kind(a.town, inn, day);
        if (kind == null) return false;
        // The inn's ground woken for the reckoning: its larder is a cask behind its bar.
        if (!level.isLoaded(inn.anchor)) level.getChunk(inn.anchor.getX() >> 4, inn.anchor.getZ() >> 4);
        Container larder = Roadside.larderBox(level, inn);
        if (larder == null) return false;
        boolean night = kind == Kind.STAY && (tod >= NIGHT_FROM || tod < MARCH_ON);
        long wait = kind == Kind.PLUNDER ? PLUNDER_TICKS : night ? (tod >= NIGHT_FROM ? 24000L - tod + MARCH_ON : MARCH_ON - tod) : MEAL_TICKS;
        long until = night && Campaigns.soon(wait) == wait ? Long.MAX_VALUE : now + Campaigns.soon(wait);
        Halt h = new Halt(p.owner, p.other, a.town, kind, night, until, night ? dt + wait : -1, c.stage);
        boolean done = kind == Kind.PLUNDER ? plunder(level, c, a, inn, larder, h, day) : serve(level, c, a, inn, larder, h, day);
        if (!done) return false;
        HALTS.put(key(c, i), h);
        if (c.stage == Campaigns.Stage.HOME) c.nextAt = Math.max(c.nextAt, now) + Campaigns.soon(wait);
        place(level, c, i, inn, h);
        return true;
    }

    // ------------------------------------------------------------------ served

    /** The army fed out of the larder and its officers given rooms, paid out of its town's treasury. False if nothing came of it. */
    static boolean serve(ServerLevel level, Campaigns.Campaign c, Armies.Army a, Roadside.House inn, Container larder, Halt h, long day) {
        List<Armies.Fighter> men = new ArrayList<>();
        for (Armies.Fighter f : a.fighters) if (!f.dead && f.order != Armies.Order.CAPTIVE) men.add(f);
        int coins = Ledger.coins(a.town);
        int fed = Math.min(men.size(), Math.min(Roadside.larder(level, inn), coins / Roadside.MEAL));
        // The officers' rooms (the night only): a bed each while beds and coin last.
        List<BlockPos> beds = h.night ? freeBeds(level, inn) : List.of();
        int left = coins - fed * Roadside.MEAL;
        for (Armies.Fighter f : men) {
            if (h.rooms.size() >= beds.size() || left < Roadside.ROOM) break;
            if (f.rank == Armies.Rank.NONE || f.down()) continue;
            h.rooms.put(f.id, beds.get(h.rooms.size()));
            left -= Roadside.ROOM;
        }
        if (fed == 0 && h.rooms.isEmpty()) {
            String why = Roadside.larder(level, inn) == 0 ? "found its larder bare" : "had not the coin in the treasury to pay";
            Villages.tell(a.town, day, "our army came by the inn on the road between " + Villages.name(inn.owner) + " and "
                + Villages.name(inn.other) + ", but " + why + ", and marched on");
            return false;
        }
        // The meals out of the larder, a dish a man.
        int eaten = take(larder, fed, null);
        fed = eaten;
        int paid = Ledger.takeCoins(a.town, fed * Roadside.MEAL + h.rooms.size() * Roadside.ROOM);
        Economy.spent(a.town, paid);
        h.fed = fed;
        h.paid = paid;
        VillageFolkEntity keeper = Roadside.keeper(level, inn);
        if (keeper != null) keeper.earn(paid);
        else Ledger.note(inn.owner, TILL + inn.other, Integer.toString((int) WarBooks.num(Ledger.note(inn.owner, TILL + inn.other), 0) + paid));
        inn.meals += fed;
        inn.guests += h.rooms.size();
        inn.takings += paid;
        // The inn's own town's half of it, its rent: an army of that town takes it home itself.
        if (a.town.equals(inn.owner) && keeper != null) {
            int owed = Math.max(0, inn.takings / 2 - inn.rent), carried = Math.min(owed, keeper.purse());
            if (carried > 0 && keeper.spend(carried)) {
                inn.rent += carried;
                h.carried = carried;
                Ledger.note(a.town, RENT, Integer.toString((int) WarBooks.num(Ledger.note(a.town, RENT), 0) + carried));
            }
        }
        Roadside.save(inn);
        for (Armies.Fighter f : men) if (h.rooms.containsKey(f.id)) ROOMS.put(f.id, h.rooms.get(f.id));
        hearten(level, a, men, fed, h.night);
        // Words: the Captain, the keeper, the chronicles, the war book, the inn's books.
        String where = "the inn on the road between " + Villages.name(inn.owner) + " and " + Villages.name(inn.other);
        String bill = fed + (fed == 1 ? " meal" : " meals") + (h.rooms.isEmpty() ? "" : " and " + h.rooms.size() + (h.rooms.size() == 1 ? " room" : " rooms"))
            + ", " + Visitors.coins(paid);
        Villages.tell(a.town, day, "our army stopped at " + where + (h.night ? " for the night" : " for a hot meal") + ": " + bill
            + " out of the treasury" + (h.carried > 0 ? "; the keeper sent " + Visitors.coins(h.carried) + " of the inn's rent home with it" : ""));
        if (!inn.owner.equals(a.town)) {
            Villages.tell(inn.owner, day, "the army of " + Villages.name(a.town) + " (" + a.size() + " strong) " + (h.night ? "stayed the night" : "stopped for a meal")
                + " at our inn on the road to " + Villages.name(inn.other) + ": " + bill);
        }
        WarBooks.course(a.town, a.enemy, day, "our army " + (h.night ? "lodged the night" : "ate") + " at " + where + ", " + bill);
        book(inn, day, Villages.name(a.town) + "'s army (" + a.size() + ")", h.night ? "the night" : "a meal", fed, h.rooms.size(), paid);
        Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
        if (cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e) {
            Armies.shout(e, h.night ? "Halt! We stop here for the night. Officers inside; the rest of you, by the road, and eat."
                : "Halt! Half an hour for a hot meal, and then on.");
        }
        if (keeper != null) {
            FolkTalk.speak(keeper, h.night ? FolkTalk.pick(keeper.getRandom(), "Soldiers! Stew for " + fed + ", and beds for the officers.",
                    "A whole army for the night! Mind the furniture.") : FolkTalk.pick(keeper.getRandom(), "Hot stew, coming out!",
                    fed + " bowls? Right you are."));
            level.playSound(null, keeper.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        }
        LOG.info("[MCA-WAR] {}'s army {} at the inn between {} and {}: {} fed, {} rooms, {} coins", Villages.name(a.town),
            h.night ? "lodges the night" : "eats", Villages.name(inn.owner), Villages.name(inn.other), fed, h.rooms.size(), paid);
        return true;
    }

    /** The inn's beds free tonight: not a player's, not a traveller's, not slept in. */
    static List<BlockPos> freeBeds(ServerLevel level, Roadside.House inn) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos head : Inn.beds(level, inn.building())) if (Roadside.bedFree(level, head)) out.add(head);
        return out;
    }

    /** Is this bed an officer's tonight? (Roadside.freeBed: not let to anybody else.) */
    static boolean roomTaken(BlockPos head) {
        return !ROOMS.isEmpty() && ROOMS.containsValue(head);
    }

    /** So much food out of the larder (all of it, with -1); into {@code into} by item if given, else eaten. How much. */
    static int take(Container larder, int n, @Nullable Map<Item, Integer> into) {
        int got = 0;
        for (int s = 0; s < larder.getContainerSize() && (n < 0 || got < n); s++) {
            ItemStack st = larder.getItem(s);
            if (!Roadside.food(st)) continue;
            int k = n < 0 ? st.getCount() : Math.min(st.getCount(), n - got);
            if (into != null) into.merge(st.getItem(), k, Integer::sum);
            st.shrink(k);
            if (st.isEmpty()) larder.setItem(s, ItemStack.EMPTY);
            got += k;
        }
        larder.setChanged();
        return got;
    }

    /** Fed men in better heart and a little mended; a night under a roof more so. */
    static void hearten(ServerLevel level, Armies.Army a, List<Armies.Fighter> men, int fed, boolean night) {
        int n = 0;
        for (Armies.Fighter f : men) {
            if (n++ >= fed) break;
            if (level.getEntity(f.id) instanceof VillageFolkEntity e && e.isAlive()) e.heal(4.0F);
        }
        int lift = fed > 0 ? (night ? NIGHT_HEART : MEAL_HEART) : 0;
        if (lift > 0 && a.morale < HEART_MOST) a.morale = Math.min(HEART_MOST, a.morale + lift);
    }

    // ------------------------------------------------------------------ plundered

    /** An enemy army takes the larder: its men eat, the rest goes on its carts. False with nothing in it to take. */
    static boolean plunder(ServerLevel level, Campaigns.Campaign c, Armies.Army a, Roadside.House inn, Container larder, Halt h, long day) {
        int food = Roadside.larder(level, inn);
        if (food <= 0) return false;
        int men = 0;
        for (Armies.Fighter f : a.fighters) if (!f.dead && f.order != Armies.Order.CAPTIVE) men++;
        int eaten = take(larder, Math.min(men, food), null);
        Map<Item, Integer> loot = new LinkedHashMap<>();
        int carried = take(larder, -1, loot);
        for (Map.Entry<Item, Integer> e : loot.entrySet()) a.supply.merge(e.getKey(), e.getValue(), Integer::sum);
        h.fed = eaten;
        h.carried = carried;
        List<Armies.Fighter> all = new ArrayList<>(a.fighters);
        hearten(level, a, all, eaten, false);
        Ledger.note(inn.owner, BARRED + a.town, Long.toString(day));
        String where = "the inn on the road between " + Villages.name(inn.owner) + " and " + Villages.name(inn.other);
        String what = eaten + " eaten there" + (carried > 0 ? " and " + carried + " carried off" : "");
        Villages.tell(inn.owner, day, "the army of " + Villages.name(a.town) + " plundered our inn on the road to " + Villages.name(inn.other)
            + ": its larder emptied, " + what);
        Villages.tell(a.town, day, "our army requisitioned the larder of " + Villages.name(inn.owner) + "'s inn on the road to "
            + Villages.name(inn.other) + ": " + what);
        WarBooks.wrong(inn.owner, a.town, day, WarAndPeace.Wrong.THEFT, "the larder of our inn on the road to " + Villages.name(inn.other) + " plundered by its army");
        WarBooks.course(inn.owner, a.town, day, "its army plundered our inn on the road to " + Villages.name(inn.other));
        WarBooks.course(a.town, inn.owner, day, "our army requisitioned the larder of " + where);
        book(inn, day, Villages.name(a.town) + "'s army (" + a.size() + ")", "plundered the larder", eaten + carried, 0, 0);
        VillageFolkEntity keeper = Roadside.keeper(level, inn);
        if (keeper != null) {
            FolkTalk.speak(keeper, FolkTalk.pick(keeper.getRandom(), "That's my larder! Thieves, the lot of you!",
                "Take it, then — and may it choke you!", Villages.name(inn.owner) + " will hear of this!"));
            level.playSound(null, keeper.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.9F, 1.0F);
        }
        Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
        if (cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e) Armies.shout(e, "Empty the larder: it's the enemy's. Eat, and load the rest.");
        LOG.info("[MCA-WAR] {}'s army plundered the inn between {} and {}: {} eaten, {} carried off", Villages.name(a.town),
            Villages.name(inn.owner), Villages.name(inn.other), eaten, carried);
        return true;
    }

    // ------------------------------------------------------------------ by the door

    /** The army drawn up by the inn: the men sitting in rows along the road before its door, the officers with rooms by their beds. */
    static void place(ServerLevel level, Campaigns.Campaign c, int i, Roadside.House inn, Halt h) {
        Armies.Army a = c.armies[i];
        Ledger.Building b = inn.building();
        Direction back = b.facing(), right = back.getClockWise();
        float face = Armies.yawTo(back.getStepX(), back.getStepZ());
        List<Armies.Fighter> order = Battles.columnOrder(a);
        order.removeIf(f -> f.order == Armies.Order.CAPTIVE);
        Armies.Army other = c.armies[1 - i];
        if (other != null) for (Armies.Fighter f : other.fighters) if (f.order == Armies.Order.CAPTIVE && !f.dead) order.add(f);
        int k = 0;
        for (Armies.Fighter f : order) {
            BlockPos bed = h.rooms.get(f.id);
            if (bed != null) {
                BlockPos spot = Trades.floorSpot(level, bed, 2);
                if (spot != null) {
                    f.place(level, spot.getX() + 0.5, spot.getZ() + 0.5, face);
                    f.ty = spot.getY();                                  // under the roof, not on it
                    f.sit = false;
                    continue;
                }
            }
            int row = k / PER_ROW, col = k % PER_ROW;
            k++;
            double across = (col - (PER_ROW - 1) / 2.0) * 1.6, out = 6.5 + row * 1.8;
            double x = b.anchor().getX() + 0.5 + right.getStepX() * across - back.getStepX() * out;
            double z = b.anchor().getZ() + 0.5 + right.getStepZ() * across - back.getStepZ() * out;
            f.speed = 0.7;
            if (f.order == Armies.Order.YIELDED) { f.order = Armies.Order.PLACE; f.tended = false; }
            f.place(level, x, z, face);
            f.sit = f.order == Armies.Order.PLACE && f.rank != Armies.Rank.CAPTAIN;
        }
    }

    /** A second of the halt: the column kept in its places (the officers abed by night: Armies.step's own look, abed). */
    static void tend(ServerLevel level, Campaigns.Campaign c, int i, Halt h) {
        long now = level.getGameTime();
        if ((now / 20) % 10 != 0) return;
        Roadside.House inn = Roadside.of(h.owner, h.other);
        if (inn != null) place(level, c, i, inn, h);
    }

    /** The halt over: the officers up, the men on their feet, and on (or called away, the campaign moved on without them). */
    static void finish(ServerLevel level, Campaigns.Campaign c, int i, Halt h, boolean marchOn) {
        HALTS.remove(key(c, i));
        for (UUID o : h.rooms.keySet()) {
            ROOMS.remove(o);
            if (level.getEntity(o) instanceof VillageFolkEntity e && e.isSleeping()) e.stopSleeping();
        }
        Armies.Army a = c.armies[i];
        if (a == null) return;
        for (Armies.Fighter f : a.fighters) f.sit = false;
        if (!marchOn) return;
        long day = level.getDayTime() / 24000L;
        if (h.night) {
            Villages.tell(a.town, day, "our army marched on at dawn from the inn on the road between " + Villages.name(h.owner) + " and " + Villages.name(h.other));
        }
        Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
        if (cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e) {
            Armies.shout(e, h.night ? "On your feet! Dawn's up and we've a road to walk." : h.kind == Kind.PLUNDER ? "Loaded? Fall in! March!" : "Fall in! On we go.");
        }
    }

    /**
     * [Armies.step, a fighter in its place] An officer with a room at the inn tonight: once by its bed, it sleeps in it
     * (true: its step is done). One who has been abed with no room now (a restart in the night, the halt over) gets up.
     */
    static boolean abed(VillageFolkEntity f, Armies.Fighter x) {
        BlockPos bed = ROOMS.isEmpty() ? null : ROOMS.get(f.getUUID());
        if (bed == null) {
            if (f.isSleeping() && f.tickCount % 20 == x.stagger) f.stopSleeping();
            return false;
        }
        if (f.isSleeping()) {
            f.hobbyNow = "asleep in a room at the inn on the road";
            return true;
        }
        if (f.distanceToSqr(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5) > 2.6 * 2.6) return false;
        f.getNavigation().stop();
        f.startSleeping(bed);
        return true;
    }

    /** [Armies.doing] What a fighter halted at an inn is about, for its card; null if it is not. */
    @Nullable
    static String doing(Armies.Fighter x) {
        if (HALTS.isEmpty() || x.army == null) return null;
        for (Map.Entry<String, Halt> e : HALTS.entrySet()) {
            Halt h = e.getValue();
            if (!h.town.equals(x.army.town)) continue;
            if (h.kind == Kind.PLUNDER) return "plundering the larder of an inn on the road";
            return ROOMS.containsKey(x.id) ? "in a room at the inn on the road" : h.night ? "resting the night by an inn on the road" : "eating at an inn on the road";
        }
        return null;
    }

    /** [Campaigns.lines, WarRoom] Where army i rests now, as ", resting the night at the inn on the road between X and Y"; "" if marching. */
    public static String atInn(Campaigns.Campaign c, int i) {
        Halt h = HALTS.isEmpty() ? null : HALTS.get(key(c, i));
        if (h == null) return "";
        String inn = "the inn on the road between " + Villages.name(h.owner) + " and " + Villages.name(h.other);
        return h.kind == Kind.PLUNDER ? ", plundering " + inn : h.night ? ", resting the night at " + inn : ", stopped for a meal at " + inn;
    }

    // ------------------------------------------------------------------ home, and the books

    /** [Campaigns.standDown] Army i home: the inn's rent it carried into the treasury, and its halts forgotten. */
    static void home(ServerLevel level, Campaigns.Campaign c, int i) {
        String key = key(c, i);
        PLANS.remove(key);
        Halt h = HALTS.remove(key);
        if (h != null) for (UUID o : h.rooms.keySet()) ROOMS.remove(o);
        Armies.Army a = c.armies[i];
        if (a == null) return;
        int rent = (int) WarBooks.num(Ledger.note(a.town, RENT), 0);
        if (rent <= 0) return;
        Ledger.forget(a.town, RENT);
        Ledger.addCoins(a.town, rent);
        Villages.tell(a.town, level.getDayTime() / 24000L, "the army brought home " + Visitors.coins(rent) + " of rent from the town's inn on the road, into the treasury");
    }

    /** [Roadside.keep] The keeper about again: what armies left on the bar for it, into its till. */
    static void till(Roadside.House inn, VillageFolkEntity keeper) {
        String s = Ledger.note(inn.owner, TILL + inn.other);
        if (s == null || s.isEmpty()) return;
        int n = (int) WarBooks.num(s, 0);
        Ledger.forget(inn.owner, TILL + inn.other);
        if (n > 0) keeper.earn(n);
    }

    /** The inn's book: who stayed, newest last, the last eight. */
    static void book(Roadside.House inn, long day, String who, String what, int meals, int rooms, int coins) {
        String k = BOOK + inn.other;
        List<String> lines = new ArrayList<>();
        String s = Ledger.note(inn.owner, k);
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        lines.add(day + "|" + who.replace('|', '/') + "|" + what + "|" + meals + "|" + rooms + "|" + coins);
        while (lines.size() > 8) lines.remove(0);
        Ledger.note(inn.owner, k, String.join("\n", lines));
    }

    /** The armies in the inn's book, newest first, in words (the Roads page, the keeper's card). */
    static List<String> bookLines(Roadside.House inn) {
        List<String> out = new ArrayList<>();
        String s = Ledger.note(inn.owner, BOOK + inn.other);
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int j = lines.length - 1; j >= 0; j--) {
            String[] p = lines[j].split("\\|", -1);
            if (p.length < 6) continue;
            long d = WarBooks.num(p[0], 0);
            out.add("Day " + (d + 1) + ": " + p[1] + ", " + p[2] + (p[2].startsWith("plunder") ? " (" + p[3] + " taken)"
                : " (" + p[3] + " meals" + ("0".equals(p[4]) ? "" : ", " + p[4] + " rooms") + ", " + p[5] + " coins)"));
        }
        return out;
    }

    /** [Roadside.line, Roadside.card] The armies the inn has had, in a line ("" with none). */
    static String booksLine(Roadside.House inn) {
        List<String> b = bookLines(inn);
        if (b.isEmpty()) return "";
        return " Armies: " + String.join("; ", b.subList(0, Math.min(3, b.size()))) + ".";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: is army i halted at an inn, and how: "stay night", "stay meal", "plunder"; "" if not. */
    public static String haltForTests(Campaigns.Campaign c, int i) {
        Halt h = HALTS.get(key(c, i));
        return h == null ? "" : h.kind == Kind.PLUNDER ? "plunder" : h.night ? "stay night" : "stay meal";
    }

    /** Tests: the officers with rooms at its inn, and how many it fed. {rooms, fed, paid, carried}. */
    public static int[] haltCountsForTests(Campaigns.Campaign c, int i) {
        Halt h = HALTS.get(key(c, i));
        return h == null ? new int[4] : new int[]{ h.rooms.size(), h.fed, h.paid, h.carried };
    }

    /** Tests: the officers abed at the inn tonight. */
    public static List<UUID> roomsForTests(Campaigns.Campaign c, int i) {
        Halt h = HALTS.get(key(c, i));
        return h == null ? List.of() : new ArrayList<>(h.rooms.keySet());
    }

    /** Tests: where along its way army i will halt (-1: nowhere). */
    public static double plannedForTests(Campaigns.Campaign c, int i) {
        Plan p = PLANS.get(key(c, i));
        return p == null ? -1 : p.along;
    }

    /** Tests: army i's way, as it is now. */
    public static List<BlockPos> wayForTests(Campaigns.Campaign c, int i) {
        return new ArrayList<>(c.ways.get(i));
    }

    /** Tests: how far along its way army i's head is. */
    public static double alongForTests(Campaigns.Campaign c, int i) {
        return c.along[i];
    }

    /** Tests: army i's head set down here and the campaign sent home (as a peace made in the field would). */
    public static void homeFromForTests(ServerLevel level, Campaigns.Campaign c, int i, BlockPos from) {
        c.ways.set(i, new ArrayList<>(List.of(from)));
        c.along[i] = 0;
        Campaigns.goHome(level, c, "the tests");
    }

    /** Tests: army i given this road (as at the march), its inn planned. */
    public static void routeForTests(Campaigns.Campaign c, int i, BlockPos from, BlockPos to) {
        Campaigns.route(c, i, from, to);
    }

    /** Tests: a second of the campaign now. */
    public static void secondForTests(ServerLevel level, Campaigns.Campaign c) {
        Campaigns.second(level, c);
    }

    /** Tests: what army i carries home (its supply) of this item. */
    public static int carriedForTests(Campaigns.Campaign c, int i, Item it) {
        Armies.Army a = c.armies[i];
        return a == null ? 0 : a.carried(it);
    }

    /** Tests: the inn's own books {takings, meals, guests, rent, its keeper's purse (-1 not about)}. */
    public static int[] innForTests(ServerLevel level, UUID x, UUID y) {
        Roadside.House h = Roadside.of(x, y);
        if (h == null) return new int[5];
        VillageFolkEntity k = Roadside.keeper(level, h);
        return new int[]{ h.takings, h.meals, h.guests, h.rent, k == null ? -1 : k.purse() };
    }

    /** Tests: what this town's army may do at the inn between x and y now: "STAY", "PLUNDER", or "". */
    public static String kindForTests(ServerLevel level, UUID town, UUID x, UUID y) {
        Roadside.House h = Roadside.of(x, y);
        Kind k = h == null ? null : kind(town, h, level.getDayTime() / 24000L);
        return k == null ? "" : k.name();
    }

    /** Tests: the food army i carries in its supply. */
    public static int foodCarriedForTests(Campaigns.Campaign c, int i) {
        Armies.Army a = c.armies[i];
        if (a == null) return 0;
        int n = 0;
        for (Map.Entry<Item, Integer> e : a.supply.entrySet()) if (Roadside.food(new ItemStack(e.getKey()))) n += e.getValue();
        return n;
    }

    /** Tests: is this folk abed in a room at an inn? */
    public static boolean abedForTests(VillageFolkEntity f) {
        return ROOMS.containsKey(f.getUUID()) && f.isSleeping();
    }

    /** Tests: how long till the campaign's next stage (in camp: its battle), in ticks from now. */
    public static long battleInForTests(ServerLevel level, Campaigns.Campaign c) {
        return c.nextAt - level.getGameTime();
    }

    /** Tests: the inn's book, in words. */
    public static List<String> bookForTests(UUID x, UUID y) {
        Roadside.House h = Roadside.of(x, y);
        return h == null ? List.of() : bookLines(h);
    }
}
