package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * [trade-world] Folk leaving an unhappy town for a better one, on foot, and towns keeping the ones they cannot spare.
 *
 * <ul>
 * <li><b>Why go.</b> Each morning a town looks at how its people are: a hungry town, folk with no bed of their own, wages
 *     paid short, a war on or lately lost, a town low in its spirits (Contentment), and what each folk feels itself. What
 *     holds a folk is its ties: its friends, the years it has lived there, a homebody's nature, being the town's only
 *     hand at its trade. Where the push is more than the ties, and there is a town at peace it knows of with room and a
 *     better life (Contentment's score, kin there, a notice for its trade), it starts to <i>think of leaving</i>: one
 *     household in a town at a time, no oftener than every other day.</li>
 * <li><b>Thinking of it.</b> For a day it talks about it (to whoever is near, and its friends answer: "Don't go!"); its
 *     card says so, and why, and where. You can talk it round: "Won't you stay?" with coin in your pockets is a raise of
 *     five coins out of your purse into its own, and that will keep most; without, your words count for what it thinks of
 *     you; a gift counts too. A master of its trade (or the town's only one) the elder may pay to keep, out of the
 *     treasury, if the treasury can spare it.</li>
 * <li><b>Going.</b> The next morning, if it still means to, it packs its own things from its house's chest, says its
 *     goodbyes on the square, and walks the road with its partner and children (JobSeekers, as anybody moving for work):
 *     off the old roll, on the new, its house given up. A town is never left below eight, and no more go in a week than it
 *     can spare (JobMarket.cannotSpare); families always go together. At the other end it is a newcomer: a home found for
 *     the household (or a bed at the camp), and work at what the town is short of.</li>
 * <li><b>On the road.</b> Halfway, it may turn back: homesick, with ties at home; or word comes the town it was making for
 *     has gone to war; or the road is not safe (ROAD_DANGER, for whatever else is out there). It walks home and takes up
 *     its old trade. Both chronicles tell every going, turning back and coming.</li>
 * <li><b>Towns compete.</b> A town short of hands posts notices on the job market (JobMarket): those notices say so when
 *     there is a house to be had, and a folk without a home of its own counts it; its ties make it harder to tempt away
 *     (needed); and a master taken on elsewhere may be kept by a counter-offer from its own elder (counterOffer).</li>
 * </ul>
 * The Roads page has the comings and goings (in, out, turned back, talked round), and the folk thinking of leaving now.
 */
public final class Migration {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Migration() {}

    /** The job market's opening number a folk moving of its own accord goes with (no notice, no road money). */
    static final int OPENING = -7;
    /** No town is left below this many. */
    static final int FLOOR = 8;
    /** How much more the push must be than the ties to think of going, and how much better the other town must be. */
    static final int THINK = 20, PULL = 12;
    /** A raise a player pays to keep a folk; how much a raise and a gift count for. */
    public static final int RAISE = 5;
    static final int RAISE_WEIGHT = 40, GIFT_WEIGHT = 15;

    /** [cross-link] How much a town's taxes push its folk to leave (the council's taxes plug in here; nothing by default). */
    public static ToIntFunction<UUID> TAXES = id -> 0;
    /** [cross-link] What makes the road unsafe at a place (outlaws, a battle), in words, or null. */
    public static java.util.function.BiFunction<ServerLevel, BlockPos, String> ROAD_DANGER = (l, p) -> null;

    /** A folk thinking of leaving: for where, since when, why (its words), what has been said to keep it, and by whom. */
    static final class Thought {
        UUID to;
        long since;
        String because = "";
        int persuasion;
        String by = "";
        long lastSaid;

        String encode() {
            return to + "|" + since + "|" + because.replace('|', '/') + "|" + persuasion + "|" + by.replace('|', '/');
        }

        @Nullable
        static Thought decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 5) return null;
            try {
                Thought t = new Thought();
                t.to = UUID.fromString(p[0]);
                t.since = Long.parseLong(p[1]);
                t.because = p[2];
                t.persuasion = Integer.parseInt(p[3]);
                t.by = p[4];
                return t;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static final String THOUGHT = "tw.mig/", ROAD = "tw.mig.road/", TRADE = "tw.mig.trade/", LAST = "tw.mig.last", LOG_NOTE = "tw.mig.log";
    private static final Map<UUID, Thought> THOUGHTS = new ConcurrentHashMap<>();
    /** Journeys whose halfway has been reached (the turning back asked once). */
    private static final Set<UUID> ASKED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> ASSESSED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        THOUGHTS.clear();
        ASKED.clear();
        ASSESSED.clear();
    }

    // ------------------------------------------------------------------ why go, and what holds it

    /** What pushes a folk to go, as so many points and the strongest reason's "because" ("for better pay"). */
    record Push(int points, String because, String words) {}

    static Push push(ServerLevel level, VillageFolkEntity f) {
        UUID home = f.ownerId();
        if (home == null) return new Push(0, "", "");
        Contentment.View view = Contentment.of(level, home);
        int pts = 0, top = 0;
        String because = "for a fresh start", words = "";
        int town = view.score() < 45 ? (45 - view.score()) : 0;
        if (town > 0) {
            pts += town;
            top = town;
            words = "the town is " + view.word();
        }
        int mood = f.persona().mood() < 40 ? 40 - f.persona().mood() : 0;
        pts += mood;
        if (view.bad().contains("not enough to eat")) {
            pts += 18;
            if (18 > top) { top = 18; because = "for bread enough to eat"; words = "there is not enough to eat"; }
        }
        if (f.bedPos() == null && Homes.homeOf(home, f.getUUID()) == null) {
            pts += 14;
            if (14 > top) { top = 14; because = "for a bed of its own"; words = "it has no bed of its own"; }
        }
        if (view.bad().contains("the wages are hardly paid") || view.bad().contains("the wages are paid short")) {
            int w = view.bad().contains("the wages are hardly paid") ? 16 : 8;
            pts += w;
            if (w > top) { top = w; because = "for better pay"; words = "the wages are paid short"; }
        }
        if (!Wars.enemies(home).isEmpty() || WarAndPeace.wearyOfWar(home) || view.bad().contains("we lost somebody")) {
            int w = WarAndPeace.wearyOfWar(home) ? 18 : 10;
            pts += w;
            if (w > top) { top = w; because = "to get away from the war"; words = "the war"; }
        }
        int tax = Math.max(0, TAXES.applyAsInt(home));
        if (tax > 0) {
            pts += tax;
            if (tax > top) { because = "to get away from the taxes"; words = "the taxes"; }
        }
        return new Push(pts, because, words);
    }

    /** What holds a folk where it is: its friends, its years there, a homebody's nature, being needed at its trade. */
    static int ties(VillageFolkEntity f) {
        int t = Math.min(20, 4 * f.life().friends().size());
        UUID home = f.ownerId();
        if (home != null) {
            long since = cameOn(home, f.getUUID());
            long day = f.level().getDayTime() / 24000L;
            if (since < 0 || day - since > 20) t += 8;                   // born here, or long settled
        }
        if (Quirks.has(f, Quirks.Quirk.HOMEBODY)) t += 15;
        if (Quirks.has(f, Quirks.Quirk.WANDERLUST)) t -= 10;
        if (JobMarket.neededAtHome(f)) t += 10;
        return Math.max(0, t);
    }

    /** [JobSeekers.needed] How much harder its ties make it to tempt away with a notice: a point for each ten of ties. */
    static int tiesForNotices(VillageFolkEntity f) {
        return Math.min(3, ties(f) / 10);
    }

    /** [JobSeekers.reasons, notice] Is there a house to be had in the town (room for a household)? */
    static boolean houseOffered(UUID town) {
        return Villages.housing(town) - Villages.headcount(town) >= 2;
    }

    /** The best town for it to go to (at peace, known, with room, a better life), and how much better; or null. */
    @Nullable
    static Villages.Village destination(ServerLevel level, VillageFolkEntity f, int household) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null) return null;
        int here = Contentment.of(level, home).score();
        Villages.Village best = null;
        int bestPull = PULL - 1;
        List<Villages.Village> known = new ArrayList<>(Diplomacy.neighboursOf(home));
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            UUID o = link.getKey().equals(home) ? link.getValue() : link.getValue().equals(home) ? link.getKey() : null;
            Villages.Village ov = o == null ? null : Villages.get(o);
            if (ov != null && !known.contains(ov)) known.add(ov);
        }
        for (Villages.Village o : known) {
            if (o.id().equals(home) || !o.dim().equals(v.dim())) continue;
            if (Wars.atWar(home, o.id()) || !Wars.enemies(o.id()).isEmpty()) continue;
            if (Villages.housing(o.id()) - Villages.headcount(o.id()) < household && Villages.headcount(o.id()) >= 4) continue;
            int pull = Contentment.of(level, o.id()).score() - here;
            if (JobSeekers.kinIn(f, o.id()) != null) pull += 15;
            if (JobMarket.shortOf(o.id(), f.stationTask()) >= 1.0) pull += 8;
            if (pull > bestPull) {
                bestPull = pull;
                best = o;
            }
        }
        return best;
    }

    /** May this folk think of going at all (never the elder, a child, a builder at its build, one just come, one busy)? */
    static boolean free(ServerLevel level, VillageFolkEntity f) {
        UUID home = f.ownerId();
        if (home == null || f.isBaby() || f.isShowcase() || f.isHired() || Visitors.is(f) || !f.isAlive()) return false;
        if (f.trip() != null || f.expedition() != null || JobSeekers.busy(f) || Interviews.busy(f)) return false;
        if (f.getUUID().equals(Villages.elder(home)) || Villages.holdsTheLead(home, f.getUUID(), level.getGameTime())) return false;
        long since = cameOn(home, f.getUUID());
        long day = level.getDayTime() / 24000L;
        return since < 0 || day - since >= 5;
    }

    /** The day a folk came to the town from elsewhere (its story in the job market's books), or -1 if it was born here. */
    static long cameOn(UUID home, UUID folk) {
        String s = Ledger.note(home, "jm.story/" + folk);
        if (s == null || !s.contains("came from")) return -1;
        int bar = s.indexOf('|');
        return JobMarket.parseLong(bar < 0 ? s : s.substring(0, bar));
    }

    // ------------------------------------------------------------------ the town's morning

    /** [TownLife.tick, every ten seconds] Thinkers talk; in the morning, one may start to think, and yesterday's decide. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
            if (!e.getKey().startsWith(THOUGHT)) continue;
            UUID who;
            try {
                who = UUID.fromString(e.getKey().substring(THOUGHT.length()));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Thought th = thought(id, who);
            if (th == null) continue;
            if (!(level.getEntity(who) instanceof VillageFolkEntity f) || !id.equals(f.ownerId())) {
                if (level.getEntity(who) == null && day - th.since < 4) continue;   // not about just now
                forget(id, who);
                continue;
            }
            if (day > th.since && t >= 1000L && t < 8000L) decide(level, v, f, th, false);
            else talkOfIt(level, f, th);
        }
        if (t < 1000L || t > 4000L || ASSESSED.getOrDefault(id, -1L) == day) return;
        ASSESSED.put(id, day);
        assess(level, v, day);
    }

    @Nullable
    static Thought thought(UUID village, UUID folk) {
        Thought t = THOUGHTS.get(folk);
        if (t != null) return t;
        t = Thought.decode(Ledger.note(village, THOUGHT + folk));
        if (t != null) THOUGHTS.put(folk, t);
        return t;
    }

    static void keep(UUID village, UUID folk, Thought t) {
        THOUGHTS.put(folk, t);
        Ledger.note(village, THOUGHT + folk, t.encode());
    }

    static void forget(UUID village, UUID folk) {
        THOUGHTS.remove(folk);
        Ledger.forget(village, THOUGHT + folk);
    }

    /** Is this folk thinking of leaving its town? */
    public static boolean thinking(VillageFolkEntity f) {
        UUID home = f.ownerId();
        return home != null && thought(home, f.getUUID()) != null;
    }

    /** The morning's look: the folk with most reason to go, if it has enough and there is somewhere to go, starts to think. */
    static void assess(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) <= FLOOR) return;
        if (day - Harbours.parse(Ledger.note(id, LAST)) < 2) return;
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) if (e.getKey().startsWith(THOUGHT)) return;   // one at a time
        VillageFolkEntity best = null;
        int bestNet = THINK - 1;
        Push bestPush = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !free(level, f)) continue;
            Push p = push(level, f);
            int net = p.points() - ties(f);
            if (net > bestNet) {
                bestNet = net;
                best = f;
                bestPush = p;
            }
        }
        if (best == null) return;
        startThinking(level, v, best, bestPush, day);
    }

    /** It starts to think of going: where, and why; it says so. False if there is nowhere better to go. */
    static boolean startThinking(ServerLevel level, Villages.Village v, VillageFolkEntity f, Push p, long day) {
        int household = JobSeekers.household(f).size();
        if (Villages.headcount(v.id()) - household < FLOOR) return false;
        Villages.Village to = destination(level, f, household);
        if (to == null) return false;
        Thought th = new Thought();
        th.to = to.id();
        th.since = day;
        th.because = p.because();
        keep(v.id(), f.getUUID(), th);
        Ledger.note(v.id(), LAST, Long.toString(day));
        f.persona().remember(day, "I've been thinking of leaving " + Villages.name(v.id()) + " for " + Villages.name(to.id()) + ", " + p.because(), 4);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've half a mind to go to " + Villages.name(to.id()) + ". They say it's better there.",
            "That's it. I'm thinking of leaving, " + p.because() + ".", "What's keeping me here? " + Villages.name(to.id()) + " might suit me better."));
        LOG.info("[MCA-MIGRATE] {} of {} thinks of leaving for {} ({}; push {}, ties {})", f.displayNameCap(), Villages.name(v.id()),
            Villages.name(to.id()), p.because(), p.points(), ties(f));
        return true;
    }

    /** Now and then, while it thinks of it, it says so; a friend near answers. */
    static void talkOfIt(ServerLevel level, VillageFolkEntity f, Thought th) {
        long now = level.getGameTime();
        if (f.isSleeping() || now - th.lastSaid < 2400L || f.getRandom().nextInt(6) != 0) return;
        th.lastSaid = now;
        String to = Villages.name(th.to);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I keep thinking about " + to + ". Maybe I'll go.",
            "If things don't change here, I'm off to " + to + ".", "They say " + to + " looks after its folk."));
        List<String> friends = new ArrayList<>();
        for (Social.Bond b : f.life().friends()) friends.add(b.name);
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (a instanceof VillageFolkEntity o && o != f && !o.isSleeping() && friends.contains(o.displayNameCap()) && o.distanceToSqr(f) < 16 * 16) {
                o.sayLater(FolkTalk.pick(o.getRandom(), "Don't go, " + f.displayNameCap() + "!", "You'd miss us. Stay a while yet.",
                    "Give it time. Things will get better."), 40);
                break;
            }
        }
    }

    /**
     * The next morning: does it still mean to go? Its push against its ties and what has been said and paid to keep it; a
     * master the elder may pay to keep. Gone, or staying, the chronicle says so.
     */
    static void decide(ServerLevel level, Villages.Village v, VillageFolkEntity f, Thought th, boolean now) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Push p = push(level, f);
        int net = p.points() - ties(f) - th.persuasion;
        Villages.Village to = Villages.get(th.to);
        if (to == null || Wars.atWar(id, th.to) || !Wars.enemies(th.to).isEmpty()) to = destination(level, f, JobSeekers.household(f).size());
        if (net >= THINK && to != null && !now && keptByTheElder(level, v, f, net)) {
            forget(id, f.getUUID());                                    // paid to stay: the elder's words are the chronicle's
            return;
        }
        if (net < THINK || to == null) {
            LOG.info("[MCA-MIGRATE] {} stays in {} (push {}, ties {}, talked round {}{})", f.displayNameCap(), Villages.name(id), p.points(),
                ties(f), th.persuasion, to == null ? ", nowhere to go" : "");
            forget(id, f.getUUID());
            String how = th.by.isEmpty() ? (to == null ? "had nowhere better to go" : "thought better of it") : "was talked round by " + th.by;
            Villages.tell(id, day, f.displayNameCap() + " had been thinking of leaving us, but stayed: " + how);
            count(id, "stayed", 1);
            logLine(id, day, f.displayNameCap() + " stayed (" + how + ")");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll stay. This is home, after all.", "All right. I'll give it another go here."));
            return;
        }
        if (!go(level, v, f, to, th.because.isEmpty() ? p.because() : th.because)) {
            forget(id, f.getUUID());
            return;
        }
        forget(id, f.getUUID());
    }

    /**
     * A master of its trade (level four or more), or the town's only hand at a trade it wants: the elder pays it to stay,
     * three days' pay out of the treasury (if the treasury can spare it over the wages), and that keeps it unless it is set
     * on going. True if it stays.
     */
    static boolean keptByTheElder(ServerLevel level, Villages.Village v, VillageFolkEntity f, int net) {
        UUID id = v.id();
        StationTask t = f.stationTask();
        boolean master = t != StationTask.NONE && f.tradeLevel(t) >= 4;
        if (!master && !JobMarket.neededAtHome(f)) return false;
        int pay = 3 * Math.max(2, JobMarket.paidNow(f));
        if (Ledger.coins(id) < pay + ShipCalls.keep(id)) return false;
        if (net >= THINK + 25) return false;                              // set on it: no money will do
        int paid = Ledger.takeCoins(id, pay);
        if (paid <= 0) return false;
        f.earn(paid);
        Economy.spent(id, paid);
        long day = level.getDayTime() / 24000L;
        String trade = t == StationTask.NONE ? "hand" : JobMarket.noun(t);
        Villages.tell(id, day, "the elder paid " + f.displayNameCap() + ", our " + trade + ", " + Visitors.coins(paid) + " to stay, and "
            + f.displayNameCap() + " stayed");
        FolkTalk.speak(f, "Well, if they'll pay me to stay... I'll stay.");
        count(id, "stayed", 1);
        logLine(id, day, f.displayNameCap() + " the " + trade + " was paid " + paid + "c to stay");
        return true;
    }

    /** Off it goes, with its household: by JobSeekers' road, as anybody moving towns. False if the town cannot spare it. */
    static boolean go(ServerLevel level, Villages.Village v, VillageFolkEntity f, Villages.Village to, String because) {
        UUID id = v.id();
        List<VillageFolkEntity> household = JobSeekers.household(f);
        long day = level.getDayTime() / 24000L;
        if (Villages.headcount(id) - household.size() < FLOOR) return false;
        String no = JobMarket.cannotSpare(level, id, f, JobSeekers.grownIn(household));
        if (no != null) {
            FolkTalk.speak(f, "I'd go, but " + no + ".");
            LOG.info("[MCA-MIGRATE] {} would leave {} for {}, but {}", f.displayNameCap(), Villages.name(id), Villages.name(to.id()), no);
            return false;
        }
        for (VillageFolkEntity m : household) {
            if (!m.isBaby()) Ledger.note(id, TRADE + m.getUUID(), m.stationTask().name());
            Ledger.note(to.id(), ROAD + m.getUUID(), id + "|" + because.replace('|', '/') + "|" + day);
        }
        StationTask trade = JobMarket.fitFor(f, to.id());
        JobSeekers.emigrate(level, f, to.id(), trade, because);
        LOG.info("[MCA-MIGRATE] {} leaves {} for {} with {} of its household ({})", f.displayNameCap(), Villages.name(id),
            Villages.name(to.id()), household.size() - 1, because);
        return true;
    }

    /**
     * [JobSeekers.leaving] A master taken on by another town (or the only hand at a trade its town wants): its elder may
     * pay it to stay rather than lose it, if its own town is not so wretched that no money would keep it. True if it stays.
     */
    static boolean counterOffer(ServerLevel level, VillageFolkEntity f, UUID to) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null || Contentment.score(home) < 25) return false;   // a miserable town keeps nobody with money
        if (!keptByTheElder(level, v, f, THINK)) return false;
        Villages.tell(to, level.getDayTime() / 24000L, f.displayNameCap() + " of " + Villages.name(home)
            + " was to come to us, but its own town paid it to stay");
        return true;
    }

    /** [JobSeekers.depart] A household has set out: the counts of both towns. */
    static void departed(UUID from, UUID to, int people) {
        count(from, "out", people);
        count(to, "in", people);
    }

    /** [Contentment.daily] A town miserable three days running loses one: it walks to the happier town (with its family). */
    public static boolean leaveInMisery(ServerLevel level, VillageFolkEntity f, Villages.Village to, String why) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null || !free(level, f)) return false;
        return go(level, v, f, to, "because " + why);
    }

    // ------------------------------------------------------------------ talked round

    /** "Won't you stay?": a raise out of the player's pockets if it has the coin, else its word. What the folk says. */
    static String persuade(ServerLevel level, VillageFolkEntity f, Player p) {
        UUID home = f.ownerId();
        Thought th = home == null ? null : thought(home, f.getUUID());
        if (th == null) return FolkTalk.pick(f.getRandom(), "Stay? I'm not going anywhere!", "Leave? Whatever gave you that idea?");
        if (Market.coinsHeld(p) >= RAISE) {
            Market.payOut(p, RAISE);
            f.earn(RAISE);
            th.persuasion += RAISE_WEIGHT;
            th.by = p.getName().getString();
            keep(home, f.getUUID(), th);
            f.persona().feelFor(p.getUUID(), p.getName().getString(), 4);
            return FolkTalk.pick(f.getRandom(), "Five coins to stay? You're kind. That's made me think again.",
                "Well! Somebody here wants me after all. I'll think about it, and I think I'll stay.");
        }
        int liking = f.persona().affinity(p.getUUID());
        int weight = liking >= 30 ? 30 : liking >= 10 ? 18 : 8;
        th.persuasion += weight;
        if (weight >= 18) th.by = p.getName().getString();
        keep(home, f.getUUID(), th);
        return liking >= 10 ? FolkTalk.pick(f.getRandom(), "Coming from you, that means something. I'll think it over.",
            "You'd miss me? Maybe I'll stay a while yet.")
            : FolkTalk.pick(f.getRandom(), "Easy to say. Words don't fill a belly.", "I'll think about it. Can't promise more.");
    }

    /** A gift given while it is thinking of going counts towards keeping it. */
    static void gifted(VillageFolkEntity f, Player p) {
        UUID home = f.ownerId();
        Thought th = home == null ? null : thought(home, f.getUUID());
        if (th == null) return;
        th.persuasion += GIFT_WEIGHT;
        if (th.by.isEmpty()) th.by = p.getName().getString();
        keep(home, f.getUUID(), th);
    }

    // ------------------------------------------------------------------ on the road

    /**
     * [JobSeekers.travel, once at the halfway point] Does a folk moving of its own accord turn back? Its town of choice gone
     * to war; the road not safe; or (now and then) homesick, with ties at home. The reason, in words, or null.
     */
    @Nullable
    static String turnBack(ServerLevel level, VillageFolkEntity f, UUID from, UUID to) {
        if (!ASKED.add(f.getUUID())) return null;
        String road = Ledger.note(to, ROAD + f.getUUID());
        if (road == null || road.isEmpty()) return null;                   // not one of ours: moving for work, or a refugee
        Villages.Village home = Villages.get(from);
        if (home == null || !Wars.enemies(from).isEmpty() && Wars.enemies(to).isEmpty()) return null;
        if (!Wars.enemies(to).isEmpty() && Wars.enemies(from).isEmpty()) return "word came that " + Villages.name(to) + " has gone to war";
        String danger = ROAD_DANGER.apply(level, f.blockPosition());
        if (danger != null) return danger;
        int friends = f.life().friends().size();
        boolean homebody = Quirks.has(f, Quirks.Quirk.HOMEBODY);
        long roll = Math.floorMod((f.getUUID().getLeastSignificantBits() ^ (level.getDayTime() / 24000L) * 31L), 100L);
        if ((friends >= 3 || homebody) && roll < (homebody ? 45 : 25)) return "homesick for " + Villages.name(from);
        return null;
    }

    /** [JobSeekers.turnBack] The household turned for home: the counts and both chronicles. */
    static void turnedBack(ServerLevel level, UUID from, UUID to, List<String> names, String why) {
        long day = level.getDayTime() / 24000L;
        String who = JobMarket.join(names);
        Villages.tell(from, day, who + " set out for " + Villages.name(to) + " but turned back halfway, " + why + ", and " + (names.size() == 1
            ? "is" : "are") + " coming home");
        Villages.tell(to, day, who + " of " + Villages.name(from) + " were on the road to us, but turned back: " + why);
        count(from, "back", names.size());
        count(from, "out", -names.size());
        count(to, "in", -names.size());
        logLine(from, day, who + " turned back on the road to " + Villages.name(to) + " (" + why + ")");
    }

    /** Its trade before it left (for a folk come home again), or null. */
    @Nullable
    static StationTask oldTrade(UUID home, UUID folk) {
        String s = Ledger.note(home, TRADE + folk);
        if (s == null || s.isEmpty()) return null;
        try {
            return StationTask.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** [JobSeekers.arrive] A folk moving of its own accord is there: its note let go, the welcome logged. */
    static void arrived(UUID town, VillageFolkEntity f, String fromName, long day, boolean head) {
        ASKED.remove(f.getUUID());
        String road = Ledger.note(town, ROAD + f.getUUID());
        if (road == null || road.isEmpty()) return;
        Ledger.forget(town, ROAD + f.getUUID());
        if (head) logLine(town, day, f.displayNameCap() + " came from " + fromName + " (" + (road.split("\\|").length > 1 ? road.split("\\|")[1] : "") + ")");
    }

    /** Was it moving of its own accord (and on the road to this town)? */
    static boolean migrant(UUID town, UUID folk) {
        String road = Ledger.note(town, ROAD + folk);
        return road != null && !road.isEmpty();
    }

    // ------------------------------------------------------------------ the books

    static void count(UUID town, String what, int n) {
        String k = "tw.mig." + what;
        Ledger.note(town, k, Long.toString(Math.max(0, Harbours.parse(Ledger.note(town, k))) + n));
    }

    static int counted(UUID town, String what) {
        return (int) Math.max(0, Harbours.parse(Ledger.note(town, "tw.mig." + what)));
    }

    static void logLine(UUID town, long day, String line) {
        String was = Ledger.note(town, LOG_NOTE);
        List<String> all = new ArrayList<>();
        all.add("day " + (day + 1) + ": " + line);
        if (was != null && !was.isEmpty()) all.addAll(List.of(was.split("\n")));
        while (all.size() > 8) all.remove(all.size() - 1);
        Ledger.note(town, LOG_NOTE, String.join("\n", all));
    }

    /** The Roads page's comings and goings, as rows. */
    static List<Tag> rows(ServerLevel level, Villages.Village v) {
        List<Tag> out = new ArrayList<>();
        for (String s : lines(level, v)) out.add(StringTag.valueOf(s));
        return out;
    }

    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("H|Comings and goings");
        int in = counted(id, "in"), outN = counted(id, "out");
        out.add("L|Folk come to live here of their own accord: " + in + "; gone elsewhere: " + outN + " (net " + (in - outN >= 0 ? "+" : "")
            + (in - outN) + "); turned back on the road: " + counted(id, "back") + "; talked round or paid to stay: " + counted(id, "stayed") + ".");
        Push town = null;
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
            if (!e.getKey().startsWith(THOUGHT)) continue;
            Thought th = Thought.decode(e.getValue());
            if (th == null) continue;
            String name = "somebody";
            try {
                if (level.getEntity(UUID.fromString(e.getKey().substring(THOUGHT.length()))) instanceof VillageFolkEntity f) name = f.displayNameCap();
            } catch (IllegalArgumentException ignored) { }
            out.add("W|" + name + " is thinking of leaving for " + Villages.name(th.to) + ", " + th.because + (th.persuasion > 0 ? " (being talked round)" : "")
                + ". Talk to it: \"Won't you stay?\"");
        }
        Contentment.View view = Contentment.of(level, id);
        out.add("M|The town is " + view.word() + " (" + view.score() + ")" + (view.bad().isEmpty() ? "" : ": " + String.join(", ", view.bad())) + ".");
        String log = Ledger.note(id, LOG_NOTE);
        if (log != null && !log.isEmpty()) for (String l : log.split("\n")) out.add("M|" + l);
        return out;
    }

    /** The card's line for a folk thinking of leaving, or "". */
    public static String cardLine(VillageFolkEntity f) {
        UUID home = f.ownerId();
        Thought th = home == null ? null : thought(home, f.getUUID());
        if (th == null) return "";
        return "thinking of leaving for " + Villages.name(th.to) + ", " + th.because + (th.persuasion > 0 ? "; being talked round" : "")
            + " (ask \"Won't you stay?\": " + RAISE + " coins of yours is a raise)";
    }

    // ------------------------------------------------------------------ tests and the admin

    /** Tests: this folk starts to think of leaving now (if it has anywhere to go): the town it would go to, or null. */
    @Nullable
    public static UUID thinkNowForTests(ServerLevel level, VillageFolkEntity f) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null) return null;
        Push p = push(level, f);
        if (!startThinking(level, v, f, p.points() > 0 ? p : new Push(THINK, "for a fresh start", ""), level.getDayTime() / 24000L)) return null;
        Thought th = thought(home, f.getUUID());
        return th == null ? null : th.to;
    }

    /** Tests: it decides now (as the next morning would have it). */
    public static void decideForTests(ServerLevel level, VillageFolkEntity f) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        Thought th = home == null ? null : thought(home, f.getUUID());
        if (v != null && th != null) decide(level, v, f, th, false);
    }

    /** Tests: its push and its ties. */
    public static int[] pushAndTiesForTests(ServerLevel level, VillageFolkEntity f) {
        return new int[]{ push(level, f).points(), ties(f) };
    }

    /** Tests: a player talks to it ("Won't you stay?"). What it said. */
    public static String persuadeForTests(ServerLevel level, VillageFolkEntity f, Player p) {
        return persuade(level, f, p);
    }

    /** Tests: the elder's counter-offer to a folk taken on by that town. True if it stays. */
    public static boolean counterOfferForTests(ServerLevel level, VillageFolkEntity f, UUID to) {
        return counterOffer(level, f, to);
    }

    /** Tests: a notice of that town's, as a folk reads it on its board (with its house, if there is one to be had). */
    public static String noticeForTests(UUID town, JobMarket.Opening o) {
        return JobSeekers.notice(town, o);
    }

    /** Tests: the counts: in, out, back, stayed. */
    public static int[] countsForTests(UUID town) {
        return new int[]{ counted(town, "in"), counted(town, "out"), counted(town, "back"), counted(town, "stayed") };
    }

    /** Tests: the halfway's question asked again on this journey. */
    public static void askAgainForTests(UUID folk) {
        ASKED.remove(folk);
    }

    /**
     * The admin's "people migrate N": the N folk with most reason to go (households counted as one) leave now for the best
     * town they know, no thinking it over; never below the floor. What came of it, in words.
     */
    public static List<String> migrateNow(ServerLevel level, Villages.Village v, int n) {
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && free(level, f)) all.add(f);
        all.sort((x, y) -> Integer.compare(push(level, y).points() - ties(y), push(level, x).points() - ties(x)));
        Set<UUID> going = new java.util.HashSet<>();
        for (VillageFolkEntity f : all) {
            if (out.size() >= n) break;
            if (going.contains(f.getUUID())) continue;
            List<VillageFolkEntity> household = JobSeekers.household(f);
            Villages.Village to = destination(level, f, household.size());
            if (to == null) {
                // By decree, to the nearest town at peace with room, whatever its looks.
                for (Villages.Village o : Diplomacy.neighboursOf(v.id())) {
                    if (!Wars.atWar(v.id(), o.id())) { to = o; break; }
                }
            }
            if (to == null) {
                out.add(f.displayNameCap() + ": nowhere to go");
                continue;
            }
            Push p = push(level, f);
            if (go(level, v, f, to, p.points() > 0 ? p.because() : "by the town's leave")) {
                for (VillageFolkEntity m : household) going.add(m.getUUID());
                out.add(f.displayNameCap() + (household.size() > 1 ? " and " + (household.size() - 1) + " of its household" : "") + " -> "
                    + Villages.name(to.id()));
            } else {
                out.add(f.displayNameCap() + ": cannot be spared");
            }
        }
        return out;
    }
}
