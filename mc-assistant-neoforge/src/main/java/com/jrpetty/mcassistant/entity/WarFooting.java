package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a town readies itself for war, and who will fight for it.
 *
 * <p>[war] The shared seam between the town's preparations and the fighting. The war bands draw their
 * fighters from {@link #militia}; the peace talks weigh what standing armed costs ({@link #dailyCost}).
 *
 * <p>[war-prep] A town on its guard (a feud: Wars.TENSION) or at war changes the way it works, and goes back
 * to its old ways at peace:
 * <ul>
 * <li><b>More guards.</b> The watch's share of the town is raised to meet the enemy: by the scouts' last
 *     report of its guards and its armour (Intel), or, with no report or a stale one, by a cautious guess
 *     from its size. Behind a wall a few hold against more. On its guard the town goes half-way there; at
 *     war, all the way, never past three in ten of its grown folk. The extra hands come out of the trades a
 *     town can spare in war (the woods, the pens, the hives, the crafts' benches), never out of the fields,
 *     the water or the hunt: what feeds the town is not cut. Every share is reckoned so the town's shares
 *     still add up to the hands it has, so the farmers' share does not move by a hair.</li>
 * <li><b>Volunteers.</b> Each morning the leader calls for volunteers to make up the watch, one a day on
 *     its guard and two at war: those who care most for safe streets come forward first (Values: the
 *     Guardians), the hardworking and the generous before the shy and the easygoing, and a folk that has
 *     drilled with the militia before one that never has. Nobody's last farmer, nobody's storekeeper, not
 *     the elder. At peace each goes back to the trade it left.</li>
 * <li><b>The work changes.</b> The smith makes arms and armour for the militia as well as the watch, and
 *     does not hold the armour back for the age; the elder orders the mines dug for the iron the arms want;
 *     the couriers carry the arms to the armoury (WarWorks).</li>
 * <li><b>Danger money.</b> A guard is paid more while the town stands armed, more again at war, and a
 *     militia hand called up a little on top of its own trade's wage (Wealth.earned).</li>
 * </ul>
 * The militia (Militia), the fortifications, the armoury and the training yard (WarWorks) and the siege
 * stores (WarStores) are its parts. Its page is /village war footing, and a panel on the News page of the
 * town's books.
 */
public final class WarFooting {

    private WarFooting() {}

    /** A scouts' report older than this many days is no longer gone by: the leader guesses. */
    public static final long STALE_DAYS = 10;
    /** The trades a town can spare hands from to stand on its walls. Not the food trades, not the mines
     *  and the smelter (the iron for the arms), not the couriers (who carry for the watch), not the scouts. */
    static final Set<StationTask> SPARE = EnumSet.of(StationTask.WOOD, StationTask.RANCH, StationTask.BEEKEEP,
        StationTask.BREW, StationTask.ENCHANT, StationTask.TAILOR, StationTask.SHOP);
    /** What feeds the town: never a hand from these to the watch while the town is short of food. */
    static final Set<StationTask> FOOD = EnumSet.of(StationTask.FARM, StationTask.FISH, StationTask.HUNT, StationTask.COOK);
    /** A working day, in hours (a thousand ticks an hour): what a hand taken off its trade costs the town. */
    static final double WORK_HOURS = 10.0;
    /** A volunteer's old trade, kept with the world (Ledger note), so it goes back to it at peace. */
    static final String VOL = "war.vol/";

    // ------------------------------------------------------------------ the seam

    /** Is this town on a war footing (at war, or on its guard)? */
    public static boolean ready(UUID village) {
        return footing(village) != Wars.Footing.PEACE;
    }

    /** Everybody who would fight for this town now: its watch, and at war its militia called up (Militia). */
    public static List<VillageFolkEntity> militia(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() == StationTask.GUARD || Militia.calledUp(f)) out.add(f);
        }
        return out;
    }

    /** How near to war the town stands, as its preparations go: Wars.footing, and peace with the wars switched off. */
    public static Wars.Footing footing(@Nullable UUID village) {
        if (village == null || !AssistantConfig.villageWars()) return Wars.Footing.PEACE;
        return Wars.footing(village);
    }

    private static final Map<UUID, long[]> FOOTING_AT = new ConcurrentHashMap<>();

    /** The footing as of a moment ago (the muster asks every tick): Wars.footing, kept two seconds. */
    static Wars.Footing footingNow(UUID village, long gameTime) {
        long[] k = FOOTING_AT.get(village);
        if (k != null && gameTime >= k[0] && gameTime - k[0] < 40L) return Wars.Footing.values()[(int) k[1]];
        Wars.Footing f = footing(village);
        FOOTING_AT.put(village, new long[]{ gameTime, f.ordinal() });
        return f;
    }

    /** Whom the town stands armed against: those it is at war with, else those it is feuding with. */
    public static List<UUID> foes(@Nullable UUID village) {
        if (village == null) return new ArrayList<>();
        List<UUID> out = Wars.enemies(village);
        if (!out.isEmpty()) return out;
        for (Villages.Village w : Villages.every()) {
            if (!w.id().equals(village) && Ledger.relation(village, w.id()) <= Diplomacy.FEUD) out.add(w.id());
        }
        return out;
    }

    /** Today, by the overworld's clock (the scouts' reports are dated by it). */
    static long today() {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return s == null ? 0L : s.overworld().getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ the enemy reckoned

    /** The enemy's strength as the leader reckons it, whether that was a guess, and how it was come by. */
    public record Reckoning(double strength, boolean guessed, String how) {}

    /**
     * What the town reckons it faces: for each foe, its guards with those in iron counting half again, by the
     * scouts' last report while it is fresh; with no report, or one more than ten days old, a cautious guess of
     * a guard for every four of its folk (a town's size is common talk, its watch is not), and two at the least.
     * On its guard the town reckons on the strongest of its foes; at war, on all of them at once.
     */
    public static Reckoning reckon(UUID us, long today) {
        boolean war = footing(us) == Wars.Footing.WAR;
        double total = 0.0, most = 0.0;
        boolean guessed = false;
        List<String> how = new ArrayList<>();
        for (UUID e : foes(us)) {
            Intel.Report r = Intel.latest(us, e);
            double s;
            if (r != null && Intel.age(r, today) <= STALE_DAYS) {
                s = r.guards() + 0.5 * r.armoured();
                how.add(Villages.name(e) + ": " + r.guards() + " guards, " + r.armoured() + " in iron (the scouts, day " + (r.day() + 1) + ")");
            } else {
                s = Math.max(2.0, Villages.headcount(e) / 4.0);
                guessed = true;
                how.add(Villages.name(e) + ": " + (r == null ? "no scout has been" : "the scouts' report is " + Intel.age(r, today) + " days old")
                    + ", so a cautious guess of " + Math.round(s) + " guards");
            }
            total += s;
            most = Math.max(most, s);
        }
        return new Reckoning(war ? total : most, guessed, how.isEmpty() ? "nobody in particular" : String.join("; ", how));
    }

    /**
     * How many guards the town wants, as a share of its hands: enough to meet what it reckons it faces (a
     * quarter fewer behind a wall), two more than its peacetime watch at the least, three in ten of its grown
     * folk at the most. On its guard, half-way from its peacetime watch to that. A shy or grumpy leader, going
     * on a guess, wants one more.
     */
    static double wantGuards(UUID village, Wars.Footing f, double peace, int hands, Reckoning r) {
        if (f == Wars.Footing.PEACE) return peace;
        double meet = r.strength() * (Watch.wall(village) != null ? 0.75 : 1.0);
        VillageFolkEntity elder = Orders.elderOf(village);
        if (r.guessed() && elder != null && (elder.life().has(Social.Trait.SHY) || elder.life().has(Social.Trait.GRUMPY))) meet += 1.0;
        double most = Math.max(peace, hands * 0.3);
        double war = Math.min(Math.max(meet, peace + 2.0), most);
        return f == Wars.Footing.WAR ? war : peace + (war - peace) * 0.5;
    }

    // ------------------------------------------------------------------ the shares

    /**
     * What the war footing does to the town's shares: the watch's share in peace and wanted now, the hands
     * added to it, what each trade it can spare gives up (in hands), and the fit the shares were reckoned at.
     */
    public record Shares(Wars.Footing footing, double peaceGuards, double wantGuards, double extra, double fit,
                         Map<StationTask, Double> given, Reckoning reckoning, int sig, long at) {}

    private static final Map<UUID, Shares> SHARES = new ConcurrentHashMap<>();
    /** Set while the peacetime shares are reckoned, so the hook leaves them as they are. */
    private static final ThreadLocal<Boolean> RECKONING = ThreadLocal.withInitial(() -> false);

    /**
     * [war-prep] The hook in Villages.target: a trade's share of the town (before the fit) on a war footing.
     * The watch's goes up by the hands it wants, and the spare trades' come down by as many between them,
     * so the shares add up to what they did, the fit is unchanged, and the fields keep every hand.
     */
    public static double share(@Nullable UUID village, StationTask trade, double t) {
        if (village == null || RECKONING.get()) return t;
        if (trade != StationTask.GUARD && !SPARE.contains(trade)) return t;
        Shares s = shares(village);
        if (s == null || s.extra() <= 0.0 || s.fit() <= 0.0) return t;
        if (trade == StationTask.GUARD) return t + s.extra() / s.fit();
        Double g = s.given().get(trade);
        return g == null ? t : Math.max(0.0, t - g / s.fit());
    }

    /** The war footing's shares for this town now (null at peace), worked out again when anything changes. */
    @Nullable
    public static Shares shares(UUID village) {
        Wars.Footing f = footing(village);
        if (f == Wars.Footing.PEACE) {
            SHARES.remove(village);
            return null;
        }
        List<AssistantEntity> folk = Villages.folkOf(village);
        long today = today();
        int sig = signature(village, f, folk, today);
        long now = System.currentTimeMillis();
        Shares had = SHARES.get(village);
        // The same town as a moment ago: the same shares (Villages.target is asked often, and this is not cheap).
        if (had != null && had.sig() == sig && now >= had.at() && now - had.at() < 2000L) return had;
        Shares s = reckonShares(village, f, folk, sig, now, today);
        SHARES.put(village, s);
        return s;
    }

    private static int signature(UUID village, Wars.Footing f, List<AssistantEntity> folk, long today) {
        int[] by = new int[StationTask.values().length];
        for (AssistantEntity a : folk) by[a.stationTask().ordinal()]++;
        int h = java.util.Arrays.hashCode(by) * 31 + f.ordinal();
        for (UUID e : foes(village)) {
            Intel.Report r = Intel.latest(village, e);
            h = h * 31 + e.hashCode() + (r == null ? 0 : (int) r.day() * 7 + r.guards() * 13 + r.armoured());
        }
        return h * 31 + (int) today;
    }

    private static Shares reckonShares(UUID village, Wars.Footing f, List<AssistantEntity> folk, int sig, long now, long today) {
        RECKONING.set(true);
        try {
            int total = Math.max(1, Math.max(folk.size(), Villages.headcount(village)));
            Villages.Age at = Villages.ageOf(village);
            int hands = Villages.hands(folk, total);
            double fit = Villages.fit(village, total, hands, at);
            Map<StationTask, Integer> have = new EnumMap<>(StationTask.class);
            for (AssistantEntity a : folk) if (a.stationTask() != StationTask.NONE) have.merge(a.stationTask(), 1, Integer::sum);
            // Each trade's share of the town as it stands in peace, in hands (Villages.share is have less it).
            Map<StationTask, Double> peace = new EnumMap<>(StationTask.class);
            for (StationTask t : StationTask.values()) {
                if (t == StationTask.NONE || !Villages.wants(village, t) || !Villages.craftReady(village, t)) continue;
                peace.put(t, have.getOrDefault(t, 0) - Villages.share(village, t));
            }
            double guards = peace.getOrDefault(StationTask.GUARD, 0.0);
            Reckoning r = reckon(village, today);
            double want = wantGuards(village, f, guards, hands, r);
            Map<StationTask, Double> given = new EnumMap<>(StationTask.class);
            double extra = 0.0;
            // A town too small for a watch (Villages: none under eleven) has its militia, and no more guards.
            if (peace.containsKey(StationTask.GUARD) && want > guards + 0.05 && fit > 0.0) {
                double part = f == Wars.Footing.WAR ? 0.6 : 0.35;
                double pool = 0.0;
                for (StationTask t : SPARE) pool += Math.max(0.0, peace.getOrDefault(t, 0.0)) * part;
                extra = Math.min(want - guards, pool);
                if (extra > 0.0) {
                    for (StationTask t : SPARE) {
                        double can = Math.max(0.0, peace.getOrDefault(t, 0.0)) * part;
                        if (can > 0.0) given.put(t, can * extra / pool);
                    }
                }
            }
            return new Shares(f, guards, want, Math.max(0.0, extra), fit, given, r, sig, now);
        } finally {
            RECKONING.set(false);
        }
    }

    /** Tests and the books: the watch's share now, in hands (the peacetime share and what the footing adds). */
    public static double guardShare(UUID village) {
        Shares s = shares(village);
        if (s != null) return s.peaceGuards() + s.extra();
        int have = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.GUARD) have++;
        return Villages.wants(village, StationTask.GUARD) ? have - Villages.share(village, StationTask.GUARD) : 0.0;
    }

    /** Tests: something into the town's stores, as a maker would put it there. */
    public static void storesForTests(ServerLevel level, Villages.Village v, net.minecraft.world.item.ItemStack s) {
        Crafts.store(level, v, s);
    }

    /** Tests: forget the shares worked out, so the next look reckons them again. */
    public static void resetForTests() {
        SHARES.clear();
        FOOTING_AT.clear();
        Militia.resetForTests();
        WarWorks.resetForTests();
    }

    // ------------------------------------------------------------------ the morning

    /** What footing the town was on at its last morning (Ledger "war.footing": the footing and the day). */
    static Wars.Footing lastFooting(UUID village) {
        String s = Ledger.note(village, "war.footing");
        if (s == null || s.isEmpty()) return Wars.Footing.PEACE;
        try {
            return Wars.Footing.valueOf(s.split("\\|", 2)[0]);
        } catch (IllegalArgumentException e) {
            return Wars.Footing.PEACE;
        }
    }

    /** The day the town last changed its footing, or -1. */
    static long footingSince(UUID village) {
        String s = Ledger.note(village, "war.footing");
        if (s == null || !s.contains("|")) return -1L;
        try {
            return Long.parseLong(s.split("\\|", 2)[1]);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * [war-prep] The leader's morning on a war footing (from Market.tick, after the leader's books): the change
     * of footing told; the volunteers called for; the militia enrolled, called up or stood down; the works and
     * the armoury seen to; the siege stores put by. At peace after a war, everything back as it was.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        com.jrpetty.mcassistant.Guard.run("war footing", () -> morningNow(level, v, day));
    }

    private static void morningNow(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Wars.Footing now = footing(id), was = lastFooting(id);
        if (now != was) {
            announce(level, v, day, was, now);
            Ledger.note(id, "war.footing", now.name() + "|" + day);
        }
        if (now == Wars.Footing.PEACE) {
            if (was != Wars.Footing.PEACE || hasVolunteers(id) || !Militia.members(id).isEmpty()) {
                Militia.standDown(level, v, day);
                sendHome(level, id, day);
            }
            return;
        }
        Shares s = shares(id);
        if (s != null) recruit(level, v, day, s);
        Militia.morning(level, v, day, now);
        WarWorks.morning(level, v, day, now);
        WarStores.morning(level, v, day, now);
    }

    /** The change of footing, told: the chronicle, the leader's words, and the morning assembly. */
    static void announce(ServerLevel level, Villages.Village v, long day, Wars.Footing was, Wars.Footing now) {
        UUID id = v.id();
        VillageFolkEntity elder = Orders.elderOf(id);
        String who = elder == null ? "The village" : Leader.leaderName(id, elder);
        String against = names(foes(id));
        String line, said;
        if (now == Wars.Footing.TENSION) {
            line = was == Wars.Footing.WAR
                ? who + " said the war with " + against + " is over but the town stays on its guard"
                : who + " put the town on its guard against " + against + ": more hands to the watch, the militia enrolled, the walls seen to";
            said = "We're on our guard. More hands to the watch, and every able body drills on the day of rest.";
        } else if (now == Wars.Footing.WAR) {
            line = who + " put the town on a war footing against " + against
                + ": the militia called up, the armoury filled, food put by for a siege";
            said = "We're at war with " + against + ". The militia to the armoury. We'll hold.";
        } else {
            line = who + " stood the town down: the militia home to their trades, the volunteers back to their work";
            said = "It's over. Hang up the swords and back to your trades, all of you. Well done.";
        }
        Villages.tell(id, day, line);
        Market.assemblyNews(id, said);
        if (elder != null) FolkTalk.speak(elder, said);
    }

    static String names(List<UUID> villages) {
        List<String> n = new ArrayList<>();
        for (UUID u : villages) n.add(Villages.name(u));
        if (n.isEmpty()) return "its neighbours";
        if (n.size() == 1) return n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    // ------------------------------------------------------------------ the volunteers

    /** How readily this folk would stand on the wall: what it cares for, its nature, and what it knows of it. */
    static int volunteerScore(VillageFolkEntity f) {
        int s = Values.weight(f, Values.Value.SAFETY);
        Social.Life life = f.life();
        if (life.has(Social.Trait.HARDWORKING)) s += 8;
        if (life.has(Social.Trait.GENEROUS)) s += 6;
        if (life.has(Social.Trait.GRUMPY)) s += 4;
        if (life.has(Social.Trait.CHEERFUL)) s += 2;
        if (life.has(Social.Trait.EASYGOING)) s -= 6;
        if (life.has(Social.Trait.SHY)) s -= 10;
        s += 3 * f.tradeLevel(StationTask.GUARD);
        Militia.Member m = Militia.member(f);
        if (m != null) s += 4 * Math.min(5, m.drills());
        if (f.stationTask() == StationTask.NONE) s += 5;
        return s;
    }

    /** May this folk give up its work for the watch? Not a child, the old, the elder, a scout or envoy away,
     *  the storekeeper or the banker, the last hand at any trade, nor a food-maker while the town is short. */
    static boolean canVolunteer(UUID village, VillageFolkEntity f, Map<StationTask, Integer> have) {
        if (f.isBaby() || !f.isAlive() || f.isOld() || f.isElder()) return false;
        if (f.expedition() != null || f.trip() != null) return false;
        StationTask t = f.stationTask();
        if (t == StationTask.NONE) return true;
        if (t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.SCOUT) return false;
        if (have.getOrDefault(t, 0) <= 1) return false;
        if (FOOD.contains(t) && (Market.hungry(village) || !Villages.overStaffed(village, t))) return false;
        return SPARE.contains(t) || Villages.overStaffed(village, t);
    }

    /** Those who would come forward for the watch, readiest first. */
    static List<VillageFolkEntity> volunteers(UUID village) {
        Map<StationTask, Integer> have = new EnumMap<>(StationTask.class);
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() != StationTask.NONE) have.merge(a.stationTask(), 1, Integer::sum);
            if (a instanceof VillageFolkEntity f) all.add(f);
        }
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : all) if (canVolunteer(village, f, have)) out.add(f);
        out.sort((a, b) -> Integer.compare(volunteerScore(b), volunteerScore(a)));
        return out;
    }

    /**
     * The leader's call for volunteers: as many as the watch is short of its war share, one a morning on its
     * guard and two at war. Each takes up the watch (ground of its own for it, or its old plot as its beat),
     * and its old trade is written down for the peace. Returns how many came forward.
     */
    static int recruit(ServerLevel level, Villages.Village v, long day, Shares s) {
        UUID id = v.id();
        int guards = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask() == StationTask.GUARD) guards++;
        int want = (int) Math.floor(s.peaceGuards() + s.extra() + 0.25);
        int most = Math.min(want - guards, s.footing() == Wars.Footing.WAR ? 2 : 1);
        if (most <= 0) return 0;
        List<String> came = new ArrayList<>();
        for (VillageFolkEntity f : volunteers(id)) {
            if (came.size() >= most) break;
            StationTask was = f.stationTask();
            if (!f.takeUpTrade(StationTask.GUARD)) f.setJob(StationTask.GUARD);
            if (f.stationTask() != StationTask.GUARD) continue;
            f.setAutonomous(true);
            Ledger.note(id, VOL + f.getUUID(), was.name());
            f.persona().remember(day, "I volunteered for the watch when " + names(foes(id)) + " threatened us", 7);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll stand on the wall. Somebody has to.",
                "Put me down for the watch. I'll not see this town taken.", "A spear for me, then. The " + was.label + " can wait."));
            came.add(f.displayNameCap() + (was == StationTask.NONE ? "" : " (a " + was.title.toLowerCase(Locale.ROOT) + ")"));
        }
        if (!came.isEmpty()) {
            Villages.tell(id, day, String.join(", ", came) + (came.size() == 1 ? " volunteered" : " volunteered")
                + " for the watch, now the town stands armed against " + names(foes(id)));
        }
        return came.size();
    }

    static boolean hasVolunteers(UUID village) {
        for (String k : Ledger.notes(village).keySet()) if (k.startsWith(VOL)) return true;
        return false;
    }

    /** Is this folk on the watch only for the war (and what was it before)? Null if not. */
    @Nullable
    public static StationTask volunteeredFrom(VillageFolkEntity f) {
        UUID v = f.ownerId();
        String s = v == null ? null : Ledger.note(v, VOL + f.getUUID());
        if (s == null || s.isEmpty()) return null;
        try {
            return StationTask.valueOf(s);
        } catch (IllegalArgumentException e) {
            return StationTask.NONE;
        }
    }

    /** At peace: each volunteer back to the trade it left. Returns who went back. */
    static List<String> sendHome(ServerLevel level, UUID village, long day) {
        Map<UUID, VillageFolkEntity> here = new java.util.HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) here.put(f.getUUID(), f);
        boolean whole = Villages.loadedCount(village) * 5 >= Villages.headcount(village) * 4;
        List<String> back = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(VOL)) continue;
            UUID u;
            try {
                u = UUID.fromString(e.getKey().substring(VOL.length()));
            } catch (IllegalArgumentException ex) {
                Ledger.forget(village, e.getKey());
                continue;
            }
            VillageFolkEntity f = here.get(u);
            if (f == null) {
                if (whole) Ledger.forget(village, e.getKey());     // gone from the town (died, moved away)
                continue;
            }
            StationTask old;
            try {
                old = StationTask.valueOf(e.getValue());
            } catch (IllegalArgumentException ex) {
                old = StationTask.NONE;
            }
            if (f.stationTask() == StationTask.GUARD && old != StationTask.GUARD) {
                if (old == StationTask.NONE) f.setJob(StationTask.NONE);
                else if (!f.takeUpTrade(old)) f.setJob(old);
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Back to the " + (old == StationTask.NONE ? "board for work" : old.label) + ". I'll not miss the night watch.",
                    "Peace. I've had my fill of the wall."));
                f.persona().remember(day, "I went back to my own work when the peace came", 5);
                back.add(f.displayNameCap());
            }
            Ledger.forget(village, e.getKey());
        }
        if (!back.isEmpty()) Villages.tell(village, day, "with the peace, " + String.join(", ", back) + " left the watch for their old trades");
        return back;
    }

    // ------------------------------------------------------------------ the work changes

    /** [war-prep] The smith's count of the watch it arms (Crafts.guards): the militia too, on a war footing. */
    public static int armsFor(@Nullable UUID village, int guards) {
        if (village == null) return guards;
        Wars.Footing f = footing(village);
        if (f == Wars.Footing.PEACE) return guards;
        int militia = f == Wars.Footing.WAR ? Militia.called(village) : (Militia.members(village).size() + 1) / 2;
        return guards + militia;
    }

    /**
     * [war-prep] The elder's order on a war footing (Orders.choose): the mines dug for iron while the stores
     * have not the bars for the arms the militia wants, else the walls manned while the watch is short.
     * Null at peace, or when the town wants neither.
     */
    @Nullable
    public static Orders.Order order(ServerLevel level, UUID village) {
        if (!ready(village)) return null;
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        int iron = Crafts.stock(level, v, s -> s.is(net.minecraft.world.item.Items.IRON_INGOT) || s.is(net.minecraft.world.item.Items.RAW_IRON));
        int short_ = WarWorks.armsShort(level, v);
        if (short_ > 0 && iron < 8 * short_ && Orders.possible(village, Orders.Order.DIG)) return Orders.Order.DIG;
        Shares s = shares(village);
        int guards = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.GUARD) guards++;
        if (s != null && guards + 0.5 < s.peaceGuards() + s.extra() && Orders.possible(village, Orders.Order.WATCH)) return Orders.Order.WATCH;
        return null;
    }

    // ------------------------------------------------------------------ the pay and the cost

    /**
     * [war-prep] Danger money, in the hundred of a folk's trade wage (Wealth.earned): a guard a tenth more while
     * the town is on its guard, a quarter more at war, and a tenth on top of that while the enemy is reckoned
     * the stronger; a militia hand called up a tenth on top of its own trade's wage. Nothing at peace.
     */
    public static int dangerPay(VillageFolkEntity f) {
        UUID v = f.ownerId();
        Wars.Footing ft = footing(v);
        if (ft == Wars.Footing.PEACE || f.isBaby()) return 0;
        if (f.stationTask() == StationTask.GUARD) {
            if (ft == Wars.Footing.TENSION) return 10;
            Shares s = shares(v);
            boolean outmatched = s != null && s.reckoning().strength() > militia(v).size();
            return outmatched ? 35 : 25;
        }
        return ft == Wars.Footing.WAR && Militia.calledUp(f) ? 10 : 0;
    }

    /**
     * What standing armed costs the town a day: the danger money paid on top of the wages, the hours of work lost
     * (the volunteers' old trades, all day; the militia's muster and drill, each day of a war), and the two
     * together in coin, the hours at what the town pays an hour's work on average.
     */
    public record Cost(int dangerPay, double hoursLost, int coins) {}

    /** [war] The day's cost of the war footing (the peace talks weigh it): nothing at peace. */
    public static Cost dailyCost(@Nullable UUID village) {
        if (village == null || footing(village) == Wars.Footing.PEACE) return new Cost(0, 0.0, 0);
        int pay = 0, wages = 0, workers = 0;
        double hours = 0.0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
            int base = Wealth.tradeWage(f.stationTask(), village);
            pay += base * dangerPay(f) / 100;
            wages += Wealth.wage(f);
            workers++;
            if (f.stationTask() == StationTask.GUARD && volunteeredFrom(f) != null) hours += WORK_HOURS;
            if (Militia.calledUp(f)) hours += Militia.MUSTER_HOURS;
        }
        double hourly = workers == 0 ? 0.0 : wages / (double) workers / WORK_HOURS;
        return new Cost(pay, hours, pay + (int) Math.round(hours * hourly));
    }

    // ------------------------------------------------------------------ telling

    /** The war footing's page: /village war footing, and the panel on the News page of the town's books. */
    public static List<String> page(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        if (v == null) return out;
        long day = level.getDayTime() / 24000L;
        Wars.Footing f = footing(village);
        long since = footingSince(village);
        out.add(switch (f) {
            case WAR -> "At war with " + names(foes(village)) + (since >= 0 ? " (on a war footing since day " + (since + 1) + ")" : "") + ".";
            case TENSION -> "On its guard against " + names(foes(village)) + (since >= 0 ? " (since day " + (since + 1) + ")" : "") + ".";
            case PEACE -> "At peace: the town works as it always has.";
        });
        int guards = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.GUARD) guards++;
        Shares s = shares(village);
        if (s != null) {
            out.add(String.format(Locale.ROOT, "The watch: %d guards; in peace its share is %.1f, the footing wants %.1f (out of the woods, the pens and the crafts: %.1f).",
                guards, s.peaceGuards(), s.wantGuards(), s.extra()));
            out.add("The enemy as reckoned: " + s.reckoning().how() + ".");
        } else {
            out.add("The watch: " + guards + (guards == 1 ? " guard." : " guards."));
        }
        List<String> vols = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity x && volunteeredFrom(x) != null) vols.add(x.displayNameCap() + (volunteeredFrom(x) == StationTask.NONE ? "" : " (a " + volunteeredFrom(x).title.toLowerCase(Locale.ROOT) + " before)"));
        }
        if (!vols.isEmpty()) out.add("Volunteers on the watch for the war: " + String.join(", ", vols) + ".");
        out.add(Militia.line(level, village));
        out.addAll(WarWorks.lines(level, v));
        out.addAll(WarStores.lines(level, v, day));
        if (f != Wars.Footing.PEACE) {
            Cost c = dailyCost(village);
            out.add(String.format(Locale.ROOT, "What it costs a day: %d coin of danger money, %.0f hours of work lost; %d coin in all.",
                c.dangerPay(), c.hoursLost(), c.coins()));
            Orders.Order o = order(level, village);
            if (o != null) out.add("The elder's war orders: " + o.title.toLowerCase(Locale.ROOT) + ".");
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    /** The board's lines on a war footing (VillageBoards.compose): none at peace. */
    public static List<String> board(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Wars.Footing f = footing(village);
        if (f == Wars.Footing.PEACE) return out;
        int guards = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.GUARD) guards++;
        out.add((f == Wars.Footing.WAR ? "RB|AT WAR with " : "RW|On our guard against ") + names(foes(village)) + ": " + guards
            + (guards == 1 ? " guard, " : " guards, ") + Militia.members(village).size() + " in the militia"
            + (f == Wars.Footing.WAR ? " (" + Militia.called(village) + " called up)" : "") + ".");
        String works = WarWorks.boardLine(village);
        if (works != null) out.add("RN|" + works);
        return out;
    }

    /** A folk's card: its part in the war footing, or null. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby() || f.ownerId() == null) return null;
        List<String> parts = new ArrayList<>();
        StationTask was = volunteeredFrom(f);
        if (was != null) parts.add("volunteered for the watch" + (was == StationTask.NONE ? "" : ", a " + was.title.toLowerCase(Locale.ROOT) + " before"));
        String m = Militia.cardLine(f);
        if (m != null) parts.add(m);
        int danger = dangerPay(f);
        if (danger > 0) parts.add("danger money " + danger + "% on its wage");
        return parts.isEmpty() ? null : capital(String.join("; ", parts));
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
