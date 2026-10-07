package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaders and envoys. How a village gets on with its neighbours is down to whoever leads it:
 * a warm-hearted elder makes friends, a prickly one takes offence, a shrewd one wants a trade
 * pact — or tribute, from a weaker neighbour — and a wary one keeps itself to itself. Two
 * elders who are alike get on; two who are opposites do not.
 *
 * <p>And it is done in person. The elder sends an envoy, a real folk on foot, down the road to
 * the neighbour. When it gets there the neighbour's bell rings, its folk gather before their
 * board to hear what the envoy has come to say, and their elder answers it there and then, in
 * its own way. The envoy walks home with the answer, and it is told at the next morning
 * assembly.
 *
 * <p>An envoy can carry:
 * <ul>
 * <li>a first greeting;</li>
 * <li>an offer of trade — bargained over before the host's board, round by round, into a deal (or
 *     not) that both towns gain by at their own prices; its caravans then run both ways on the agreed
 *     days with the agreed goods, and the coin is carried (TradeTalks, TradeDeals);</li>
 * <li>an alliance;</li>
 * <li>peace, with gifts out of the stores;</li>
 * <li>a demand for tribute;</li>
 * <li>a complaint about the boundary;</li>
 * <li>a gift, from an open-handed elder.</li>
 * </ul>
 */
public final class Envoys {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Envoys() {}

    /** How a leader deals with other villages: from the first of its elder's traits. */
    public enum Temper {
        WARM("warm-hearted", 1), FRIENDLY("friendly", 1), GENEROUS("open-handed", 1), EASY("easygoing", 0),
        CURIOUS("curious", 0), STEADY("steady", 0), SHREWD("shrewd", 0), WARY("wary", 0), PRICKLY("prickly", -1);

        public final String words;
        /** What it does for its village's relations with everybody, a day. */
        public final int warmth;

        Temper(String words, int warmth) {
            this.words = words;
            this.warmth = warmth;
        }

        /** Says yes easily. */
        boolean kindly() { return this == WARM || this == FRIENDLY || this == GENEROUS || this == EASY; }
    }

    /** What an envoy has come about. */
    public enum Errand {
        GREETING("to greet them"), TRADE("to offer them trade"), ALLIANCE("to offer them an alliance"),
        PEACE("with gifts, to make peace"), TRIBUTE("to demand tribute"), COMPLAINT("with a complaint about the boundary"),
        GIFT("with a gift"),
        // [war-peace] A herald with an ultimatum, a call to arms to an ally, or an ally's guard to the walls (WarAndPeace).
        WAR("on the business of war");

        public final String purpose;
        Errand(String purpose) { this.purpose = purpose; }
    }

    /** How long between one village's envoys to the same neighbour. */
    static final long DAYS_BETWEEN = 3;
    /** How long an envoy waits for an audience before it says its piece to whoever is there and goes. */
    static final int PATIENCE = 6000;

    private static final Map<String, Long> SENT = new ConcurrentHashMap<>();
    /** Envoys waiting for an audience, by the village they are visiting. */
    private static final Map<UUID, List<UUID>> WAITING = new ConcurrentHashMap<>();
    /** Envoys whose audience is over: free to go home. */
    private static final Map<UUID, Boolean> HEARD = new ConcurrentHashMap<>();
    /** What a returning envoy has to tell, for the next morning assembly. */
    private static final Map<UUID, List<String>> REPORTS = new ConcurrentHashMap<>();
    /** The last few things that happened between villages, for the board and for gossip. */
    private static final Map<UUID, String> LATEST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SENT.clear();
        WAITING.clear();
        HEARD.clear();
        REPORTS.clear();
        LATEST.clear();
    }

    // ------------------------------------------------------------------ leaders

    /** A leader's temper, from its traits. */
    public static Temper of(Social.Life life) {
        if (!life.rolled()) return Temper.STEADY;
        return switch (life.traits().get(0)) {
            case CHEERFUL -> Temper.WARM;
            case SOCIABLE -> Temper.FRIENDLY;
            case GENEROUS -> Temper.GENEROUS;
            case EASYGOING -> Temper.EASY;
            case CURIOUS -> Temper.CURIOUS;
            case HARDWORKING -> Temper.SHREWD;
            case SHY -> Temper.WARY;
            case GRUMPY -> Temper.PRICKLY;
        };
    }

    @Nullable
    static VillageFolkEntity leader(UUID village) {
        UUID elder = Villages.elder(village);
        if (elder == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && a.getUUID().equals(elder) && f.isAlive()) return f;
        }
        return null;
    }

    /** The temper of a village's leader (remembered, for when the elder is out of reach). */
    public static Temper temper(UUID village) {
        VillageFolkEntity f = leader(village);
        if (f != null && f.life().rolled()) {
            Temper t = of(f.life());
            if (!t.name().equals(Ledger.note(village, "temper"))) Ledger.note(village, "temper", t.name());
            return t;
        }
        String was = Ledger.note(village, "temper");
        if (was != null) {
            try { return Temper.valueOf(was); } catch (IllegalArgumentException ignored) { }
        }
        return Temper.STEADY;
    }

    /** Do the two elders get on? Alike, they do; opposites, they do not. */
    public static int chemistry(UUID a, UUID b) {
        VillageFolkEntity x = leader(a), y = leader(b);
        if (x == null || y == null || !x.life().rolled() || !y.life().rolled()) return 0;
        if (x.life().clashesWith(y.life())) return -1;
        for (Social.Trait t : x.life().traits()) if (y.life().has(t)) return 1;
        return 0;
    }

    /** "Our elder is a shrewd one" — for talk. */
    public static String leaderNote(UUID village) {
        String name = Villages.elderName(village);
        if (name.isEmpty()) return "";
        Temper t = temper(village);
        String how = switch (t) {
            case WARM -> "makes friends wherever it goes";
            case FRIENDLY -> "is always sending somebody off to see the neighbours";
            case GENEROUS -> "would give the shirt off its back, to anybody";
            case EASY -> "lets most things go";
            case CURIOUS -> "wants to know everybody";
            case STEADY -> "keeps a steady hand";
            case SHREWD -> "drives a hard bargain";
            case WARY -> "doesn't trust outsiders";
            case PRICKLY -> "takes offence at the drop of a hat";
        };
        return "Elder " + name + " is " + t.words + " — " + how + ".";
    }

    // ------------------------------------------------------------------ pacts

    public static boolean pact(UUID a, UUID b) {
        return "trade".equals(Ledger.note(a, "pact/" + b));
    }

    public static boolean allied(UUID a, UUID b) {
        String s = Ledger.note(a, "ally/" + b);
        return s != null && !s.isEmpty();
    }

    static void sign(UUID a, UUID b, long day) {
        Ledger.note(a, "pact/" + b, "trade");
        Ledger.note(b, "pact/" + a, "trade");
        String line = Villages.name(a) + " and " + Villages.name(b) + " agreed to trade";
        Villages.tell(a, day, line);
        Villages.tell(b, day, line);
    }

    static void tear(UUID a, UUID b, long day) {
        if (!pact(a, b) && !allied(a, b)) return;
        Ledger.note(a, "pact/" + b, "");
        Ledger.note(b, "pact/" + a, "");
        Ledger.note(a, "ally/" + b, "");
        Ledger.note(b, "ally/" + a, "");
        String line = "the agreements between " + Villages.name(a) + " and " + Villages.name(b) + " were torn up";
        Villages.tell(a, day, line);
        Villages.tell(b, day, line);
    }

    static void swear(UUID a, UUID b, long day) {
        Ledger.note(a, "ally/" + b, Long.toString(day));
        Ledger.note(b, "ally/" + a, Long.toString(day));
        if (Ledger.relation(a, b) < Diplomacy.ALLIANCE) Ledger.relate(a, b, Diplomacy.ALLIANCE - Ledger.relation(a, b));
    }

    /** A neighbour pact goes sour: when two villages fall into a feud, their agreements go. */
    static void onTerms(UUID a, UUID b, Diplomacy.Terms t, long day) {
        if (t == Diplomacy.Terms.FEUD || t == Diplomacy.Terms.UNEASY && allied(a, b)) tear(a, b, day);
    }

    // ------------------------------------------------------------------ sending

    /** Once a day for each pair of neighbours (Diplomacy.daily): does either elder send somebody? */
    static void consider(ServerLevel level, Villages.Village a, Villages.Village b, long day, java.util.Random rng) {
        String key = Ledger.pair(a.id(), b.id());
        if (WarAndPeace.quiet(a.id(), b.id())) return;                // [war-peace] at war or in a quarrel: the war's own envoys only
        Long last = SENT.get(key);
        if (last != null && day - last < DAYS_BETWEEN) return;
        if (travelling(a.id(), b.id())) return;
        Villages.Village first = rng.nextBoolean() ? a : b, second = first == a ? b : a;
        for (Villages.Village[] pair : new Villages.Village[][]{ { first, second }, { second, first } }) {
            Errand e = choose(level, pair[0], pair[1], day, rng);
            if (e != null && send(level, pair[0], pair[1], e, day)) {
                SENT.put(key, day);
                return;
            }
        }
    }

    /** What this village's elder would send its neighbour, if anything, today. */
    @Nullable
    static Errand choose(ServerLevel level, Villages.Village from, Villages.Village to, long day, java.util.Random rng) {
        UUID x = from.id(), y = to.id();
        Temper t = temper(x);
        int keen = switch (t) {
            case FRIENDLY, CURIOUS, SHREWD, WARM, GENEROUS -> 3;
            case EASY, STEADY, PRICKLY -> 2;
            case WARY -> 1;
        };
        if (rng.nextInt(6) >= keen) return null;
        int r = Ledger.relation(x, y);
        Diplomacy.Terms terms = Diplomacy.terms(r);
        boolean crowded = Diplomacy.apart(from, to) < Diplomacy.CROWDED;
        boolean bigger = Villages.folkOf(x).size() >= Villages.folkOf(y).size() * 3 / 2 + 2;
        if (Ledger.note(x, "envoyed/" + y) == null && Ledger.note(y, "envoyed/" + x) == null) return Errand.GREETING;
        if (terms == Diplomacy.Terms.FEUD || terms == Diplomacy.Terms.UNEASY) {
            if (t.kindly() || (t == Temper.STEADY || t == Temper.CURIOUS) && rng.nextBoolean()) return Errand.PEACE;
            if (bigger && (t == Temper.SHREWD || t == Temper.PRICKLY) && Diplomacy.tributeDue(y, day)) return Errand.TRIBUTE;
            if (t == Temper.PRICKLY && crowded) return Errand.COMPLAINT;
            return null;
        }
        if (bigger && (t == Temper.SHREWD || t == Temper.PRICKLY) && r <= 10 && Diplomacy.tributeDue(y, day)) return Errand.TRIBUTE;
        if (crowded && t == Temper.PRICKLY && r < Diplomacy.FRIENDLY) return Errand.COMPLAINT;
        // [econ-trade] Trade talks when the two towns' books say there is something to trade, or the deal standing
        // between them is near its end; not oftener than every few days (TradeDeals.talksDue).
        if (t != Temper.WARY && r >= Diplomacy.FRIENDLY - 15 && TradeDeals.talksDue(level, x, y, day)) return Errand.TRADE;
        if (!allied(x, y) && r >= Diplomacy.ALLIANCE - 15 && t != Temper.WARY && t != Temper.PRICKLY) return Errand.ALLIANCE;
        if (t == Temper.GENEROUS && r >= 0 && com.jrpetty.mcassistant.AssistantConfig.villagesShareGoods()
                && !Caravans.load(level, from, y, true, false).isEmpty()) return Errand.GIFT;
        if (r >= -5 && (t == Temper.FRIENDLY || t == Temper.CURIOUS || t == Temper.WARM) && rng.nextInt(3) == 0) return Errand.GREETING;
        return null;
    }

    /** An envoy of one of them is on the road between them. */
    static boolean travelling(UUID a, UUID b) {
        for (UUID v : new UUID[]{ a, b }) {
            for (AssistantEntity x : Villages.folkOf(v)) {
                if (x instanceof VillageFolkEntity f && f.trip() != null && f.trip().errand != null
                        && (f.trip().to.equals(a) && f.trip().from.equals(b) || f.trip().to.equals(b) && f.trip().from.equals(a))) return true;
            }
        }
        return false;
    }

    /** Pick an envoy, give it what it carries, and set it on the road. Returns whether one went. */
    public static boolean send(ServerLevel level, Villages.Village from, Villages.Village to, Errand errand, long day) {
        VillageFolkEntity envoy = chooseEnvoy(from, errand);
        if (envoy == null) return false;
        envoy.clearQueue();
        envoy.getNavigation().stop();
        Caravans.Trip t = new Caravans.Trip(from.id(), to.id(), Caravans.way(from, to));
        t.errand = errand;
        t.gainedTick = envoy.tickCount;
        // What it carries: gifts out of the stores, what the neighbour is short of first; or coin.
        if (errand == Errand.PEACE || errand == Errand.GIFT) {
            int lots = 0;
            for (ItemStack s : Caravans.load(level, from, to.id(), false, true)) {
                // A gift is a gift, not a cartload: two lots, sixteen of each at most; the rest stays home.
                ItemStack gift = lots < 2 ? s.split(Math.min(16, s.getCount())) : ItemStack.EMPTY;
                if (!gift.isEmpty()) lots++;
                ItemStack left = gift.isEmpty() ? ItemStack.EMPTY : envoy.insertItem(gift);
                if (!left.isEmpty()) Market.intoStores(level, from.id(), left);
                if (!s.isEmpty()) Market.intoStores(level, from.id(), s);
            }
            if (errand == Errand.PEACE) t.purse = Ledger.takeCoins(from.id(), Math.min(8, Ledger.coins(from.id()) / 4));
        }
        t.purse += WarAndPeace.peacePurse(level, from.id(), to.id(), errand);   // [war-peace] what a white flag will concede, in coin
        envoy.trip(t);
        Ledger.note(from.id(), "envoyed/" + to.id(), Long.toString(day));
        String fromName = Villages.name(from.id()), toName = Villages.name(to.id());
        Villages.tell(from.id(), day, "the elder sent " + envoy.displayNameCap() + " to " + toName + " " + errand.purpose);
        envoy.persona().remember(day, "the elder sent me to " + toName + " " + errand.purpose, 5);
        FolkTalk.speak(envoy, switch (errand) {
            case GREETING -> "Off to " + toName + " to say hello from all of us!";
            case TRADE -> "Off to " + toName + " — we want to trade with them.";
            case ALLIANCE -> "To " + toName + ", with the elder's hand in friendship.";
            case PEACE -> "To " + toName + ", to make peace. Wish me luck.";
            case TRIBUTE -> "Off to " + toName + " to collect what they owe us.";
            case COMPLAINT -> "To " + toName + ". The elder has words for them.";
            case GIFT -> "Taking a few things over to " + toName + ". Neighbourly, isn't it?";
            case WAR -> "To " + toName + ", on the elder's business. Grave business.";   // [war-peace]
        });
        LATEST.put(from.id(), envoy.displayNameCap() + " went to " + toName + " " + errand.purpose);
        LOG.info("[MCA-ENVOY] {} sends {} to {} {} (temper {}, relation {})",
            fromName, envoy.displayNameCap(), toName, errand, temper(from.id()), Ledger.relation(from.id(), to.id()));
        return true;
    }

    /** The one to send: the elder itself for an alliance or a peace, else the most sociable hand free to go. */
    @Nullable
    static VillageFolkEntity chooseEnvoy(Villages.Village v, Errand errand) {
        long now = 0;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        UUID elder = Villages.elder(v.id());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isSleeping() || f.trip() != null) continue;
            if (f.isHired() || Nether.away(f) || Drover.busy(f)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            now = f.level().getGameTime();
            boolean isElder = f.getUUID().equals(elder);
            if (Villages.holdsTheLead(v.id(), f.getUUID(), now) && !isElder) continue;
            int score = 0;
            if (f.life().has(Social.Trait.SOCIABLE)) score += 30;
            if (f.life().has(Social.Trait.CHEERFUL)) score += 15;
            if (f.life().has(Social.Trait.CURIOUS)) score += 10;
            if (f.life().has(Social.Trait.SHY)) score -= 30;
            if (f.life().has(Social.Trait.GRUMPY)) score += errand == Errand.COMPLAINT || errand == Errand.TRIBUTE ? 25 : -20;
            if (isElder) score += errand == Errand.ALLIANCE || errand == Errand.PEACE ? 100 : -40;
            if (f.stationTask() == AssistantEntity.StationTask.HAUL) score += 10;
            score += Math.min(20, f.veteranLevel());
            score += Quirks.envoyPull(f);                                   // [perks] a Smooth-talker, a wanderer; never a homebody
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ there

    /** The envoy has reached the village it was sent to: it asks to be heard, and waits. */
    static void arrived(ServerLevel level, VillageFolkEntity envoy, Caravans.Trip t) {
        if (WarAndPeace.garrisonArrived(level, envoy, t)) return;      // [war-peace] an ally's guard to the walls, or home from them
        t.waiting = true;
        t.waitSince = level.getGameTime();
        WAITING.computeIfAbsent(t.to, k -> new ArrayList<>()).add(envoy.getUUID());
        HEARD.remove(envoy.getUUID());
        long day = level.getDayTime() / 24000L;
        String from = Villages.name(t.from);
        FolkTalk.speak(envoy, "Greetings from " + from + "! I've come to see your elder.");
        Villages.tell(t.to, day, "an envoy came from " + from + ", " + envoy.displayNameCap() + ", " + t.errand.purpose);
        LATEST.put(t.to, "an envoy from " + from + " came " + t.errand.purpose);
    }

    /** Is this folk a visiting envoy? The village it is visiting, or null. */
    @Nullable
    public static UUID visiting(VillageFolkEntity f) {
        Caravans.Trip t = f.trip();
        return t != null && t.errand != null && t.waiting ? t.to : null;
    }

    /** The first envoy waiting to be heard here, if it is near enough. */
    @Nullable
    static VillageFolkEntity waitingAt(ServerLevel level, Villages.Village host) {
        List<UUID> list = WAITING.get(host.id());
        if (list == null || list.isEmpty()) return null;
        list.removeIf(u -> !(level.getEntity(u) instanceof VillageFolkEntity f) || !f.isAlive() || visiting(f) == null);
        for (UUID u : list) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.blockPosition().distSqr(host.centre()) < 64 * 64) return f;
        }
        return null;
    }

    /** While it waits: to the board, to stand and look about; then, heard or out of patience, home. */
    static void waitThere(ServerLevel level, VillageFolkEntity envoy, Caravans.Trip t) {
        if (WarAndPeace.onGarrison(level, envoy, t)) return;           // [war-peace] an ally's guard on the walls till the peace
        if (Assemblies.attend(envoy, level)) return;
        Villages.Village host = Villages.get(t.to), home = Villages.get(t.from);
        if (host == null || home == null) {
            homeward(level, envoy, t, null);
            return;
        }
        if (Boolean.TRUE.equals(HEARD.get(envoy.getUUID())) || t.outcome != null) {
            homeward(level, envoy, t, host);
            return;
        }
        if (level.getGameTime() - t.waitSince > PATIENCE) {
            // Nobody came to hear it: it says its piece to whoever is about, and the answer
            // is what the elder would have said.
            Answer a = answer(level, host.id(), t.from, t.errand, envoy, t);
            if (a.effect() != null) com.jrpetty.mcassistant.Guard.run("envoy answer", a.effect());
            FolkTalk.speak(envoy, "Nobody to hear me? Then I'll take it that " + (a.yes() ? "it's yes." : "it's no."));
            homeward(level, envoy, t, host);
            return;
        }
        BlockPos at = VillageBoards.lectern(host.id());
        if (at == null) at = host.centre();
        BlockPos spot = at.relative(net.minecraft.core.Direction.from2DDataValue(Math.floorMod(envoy.getUUID().hashCode(), 4)), 4);
        if (envoy.blockPosition().distSqr(spot) > 9 && (envoy.getNavigation().isDone() || envoy.tickCount % 100 == 0)) {
            envoy.walkTo(spot, 0.7D);
        }
    }

    /** The audience is over: free to go. */
    static void heard(UUID envoy) {
        HEARD.put(envoy, true);
    }

    /** Turn for home, with whatever it was given. */
    static void homeward(ServerLevel level, VillageFolkEntity envoy, Caravans.Trip t, @Nullable Villages.Village host) {
        t.waiting = false;
        List<UUID> list = WAITING.get(t.to);
        if (list != null) list.remove(envoy.getUUID());
        HEARD.remove(envoy.getUUID());
        Villages.Village home = Villages.get(t.from);
        if (host == null || home == null) {
            Caravans.abandon(level, envoy);
            return;
        }
        t.back = true;
        t.way.clear();
        t.way.addAll(Caravans.way(host, home));
        t.at = 0;
        t.best = Double.MAX_VALUE;
        t.gainedTick = envoy.tickCount;
        FolkTalk.speak(envoy, FolkTalk.pick(envoy.getRandom(), "Thank you for hearing me. Farewell!", "I'll be on my way, then.",
            "Home to " + Villages.name(t.from) + " with your answer."));
    }

    /** Home again: it tells the elder (and, at the next morning assembly, everybody). */
    static void home(ServerLevel level, VillageFolkEntity envoy, Caravans.Trip t) {
        long day = level.getDayTime() / 24000L;
        String there = Villages.name(t.to);
        String outcome = t.outcome == null ? "they would not see me" : t.outcome;
        if (t.purse > 0) {
            Ledger.addCoins(t.from, t.purse);
            outcome += " — and I've brought back " + t.purse + " coins";
        }
        Villages.tell(t.from, day, envoy.displayNameCap() + " came back from " + there + ": " + outcome);
        REPORTS.computeIfAbsent(t.from, k -> new ArrayList<>()).add(envoy.getUUID() + "|" + capital(envoy.displayNameCap()
            + " is back from " + there + ": " + outcome + "."));
        LATEST.put(t.from, envoy.displayNameCap() + " came back from " + there + ": " + outcome);
        envoy.persona().remember(day, "I came back from " + there + ": " + outcome, 4);
        FolkTalk.speak(envoy, "Back from " + there + "! " + capital(outcome) + ".");
        LOG.info("[MCA-ENVOY] {} home from {}: {}", envoy.displayNameCap(), there, outcome);
    }

    /** For the morning assembly: what the envoys have come back with (said once). */
    static List<String[]> reports(UUID village) {
        List<String> list = REPORTS.remove(village);
        List<String[]> out = new ArrayList<>();
        if (list == null) return out;
        for (String s : list) out.add(s.split("\\|", 2));
        return out;
    }

    // ------------------------------------------------------------------ the audience

    /** The host's answer: yes or no, what its elder says, and what comes of it. */
    record Answer(boolean yes, String said, @Nullable Runnable effect) {}

    /** What the envoy says on arriving: who sent it, and what it wants. */
    static String asks(UUID from, UUID host, Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        String war = WarAndPeace.asks(from, host, errand, t);             // [war-peace] the ultimatum, the call to arms, the white flag
        if (war != null) return war;
        String fn = Villages.name(from), elder = Villages.elderName(from);
        String sender = elder.isEmpty() ? "The folk of " + fn : "Elder " + elder + " of " + fn;
        return switch (errand) {
            case GREETING -> sender + " sends greetings. We'd like to know our neighbours.";
            case TRADE -> sender + " asks: will you trade with us? Caravans both ways, fair prices.";
            case ALLIANCE -> sender + " offers you an alliance. Stand by us, and we'll stand by you.";
            case PEACE -> sender + " wants an end to the bad blood — and sends these gifts" + (t.purse > 0 ? ", and " + t.purse + " coins" : "") + ".";
            case TRIBUTE -> sender + " says you owe " + Diplomacy.tributeAsked(from) + " coins in tribute. Pay up.";
            case COMPLAINT -> sender + " says your folk are on our side of the boundary. Keep off our land!";
            case GIFT -> sender + " sends you a few things from our stores, with our good wishes.";
            case WAR -> sender + " sends word of war.";                    // [war-peace]
        };
    }

    /** What the host's elder says to it — by its temper, how the villages stand, and what is in it for them. */
    static Answer answer(ServerLevel level, UUID host, UUID from, Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        Answer war = WarAndPeace.answer(level, host, from, errand, envoy, t);   // [war-peace] yield, bargain or refuse; join; make peace
        if (war != null) return war;
        Answer ruled = PlayerLeader.ruling(level, host, from, errand, envoy, t); // [player-civic] the answer a player who leads gave
        if (ruled != null) return ruled;
        Temper ht = temper(host);
        int r = Ledger.relation(host, from);
        int chem = chemistry(host, from);
        long day = level.getDayTime() / 24000L;
        String fn = Villages.name(from);
        UUID envoyId = envoy.getUUID();
        int warmth = (ht.kindly() ? 15 : 0) + (ht == Temper.PRICKLY ? -15 : 0) + (ht == Temper.WARY ? -10 : 0) + chem * 10
            + Perks.envoyWarmth(from, envoy);                       // [perks] a Diplomat's envoy, a Smooth-talker sent
        switch (errand) {
            case GREETING -> {
                if (ht == Temper.WARY || ht == Temper.PRICKLY && r < 0) {
                    return new Answer(false, "Greetings. We'll see what sort of neighbours you are.", () -> {
                        Ledger.relate(host, from, 1);
                        t.outcome = "they were polite, but cool";
                    });
                }
                return new Answer(true, "Welcome, friend! Tell " + fn + " they're always welcome here.", () -> {
                    Ledger.relate(host, from, 5 + Math.max(0, chem * 3));
                    t.outcome = "they made me very welcome";
                });
            }
            case TRADE -> {
                // [econ-trade] Not a yes or a no any more: the envoy's offer and want lists against the host's books, and
                // the bargaining, round by round, each side by its own prices (TradeTalks). Heard before the board, the
                // rounds are said aloud (Assemblies); here, with nobody to hear it, the answer comes by word of mouth.
                return TradeTalks.answer(level, host, from, envoy, t);
            }
            case ALLIANCE -> {
                int score = r + warmth + (pact(host, from) ? 10 : 0);
                if (score >= Diplomacy.ALLIANCE - 5 && ht != Temper.WARY) {
                    return new Answer(true, "Then we are allies, " + fn + " and us. Let all of you hear it!", () -> {
                        swear(host, from, day);
                        Diplomacy.announce(host, from, Ledger.relation(host, from), day);
                        Villages.tell(host, day, Villages.name(host) + " and " + fn + " swore an alliance before the village board");
                        Villages.tell(from, day, Villages.name(host) + " and " + fn + " swore an alliance");
                        t.outcome = "we are allies now";
                    });
                }
                return new Answer(false, "We're friends, and glad of it. An alliance? Not yet.", () -> {
                    Ledger.relate(host, from, 2);
                    t.outcome = "they want to stay friends, but no alliance yet";
                });
            }
            case PEACE -> {
                int gifts = giftValue(envoy) + t.purse;
                int score = r + warmth + gifts * 2 + (ht == Temper.EASY ? 20 : 0);
                if (score > Diplomacy.FEUD + 10) {
                    return new Answer(true, FolkTalk.pick(envoy.getRandom(), "Enough quarrelling. We accept, with thanks.",
                        "Let it be forgotten, then. Peace."), () -> {
                        unloadGifts(level, envoy, host);
                        if (t.purse > 0) { Ledger.addCoins(host, t.purse); t.purse = 0; }
                        Ledger.relate(host, from, 25);
                        Diplomacy.announce(host, from, Ledger.relation(host, from), day);
                        t.outcome = "they accepted our gifts and made peace";
                    });
                }
                return new Answer(false, "Gifts won't mend what " + fn + " did. Take them home.", () -> {
                    Ledger.relate(host, from, 3);
                    t.outcome = "they sent our gifts back — they are not ready for peace";
                });
            }
            case TRIBUTE -> {
                int want = Diplomacy.tributeAsked(from);
                boolean meek = ht == Temper.WARY || ht == Temper.EASY || ht == Temper.GENEROUS || ht == Temper.STEADY;
                if (ht != Temper.PRICKLY && Ledger.coins(host) >= want && (meek || r > Diplomacy.FEUD)) {
                    return new Answer(true, "Take it, then. And tell " + fn + " we won't forget this.", () -> {
                        int paid = Ledger.takeCoins(host, want);
                        t.purse += paid;
                        Diplomacy.paidTribute(host, day);
                        Ledger.relate(host, from, 2);
                        t.outcome = "they paid the tribute, " + paid + " coins, and they resent it";
                    });
                }
                return new Answer(false, ht == Temper.PRICKLY ? "Tribute? Get out of " + Villages.name(host) + "!"
                    : "We haven't got it, and we wouldn't give it you if we had.", () -> {
                    Diplomacy.paidTribute(host, day);
                    Ledger.relate(host, from, -12);
                    t.outcome = "they refused the tribute";
                });
            }
            case COMPLAINT -> {
                if (ht.kindly() || ht == Temper.STEADY && r > -20) {
                    return new Answer(true, "We're sorry for it. We'll keep to our side.", () -> {
                        Ledger.relate(host, from, 4);
                        t.outcome = "they said sorry, and promised to keep to their side";
                    });
                }
                return new Answer(false, "Your land? It's ours, and always was!", () -> {
                    Ledger.relate(host, from, -6);
                    t.outcome = "they told us the land is theirs";
                });
            }
            case GIFT -> {
                return new Answer(true, "How kind! Thank " + fn + " for us.", () -> {
                    unloadGifts(level, envoy, host);
                    Ledger.relate(host, from, 6);
                    t.outcome = "they were delighted with the gifts";
                });
            }
        }
        return new Answer(false, "", null);
    }

    /** What the gifts it carries are worth, in coin. */
    static int giftValue(VillageFolkEntity envoy) {
        double v = 0;
        for (ItemStack s : envoy.getInventoryItems()) {
            Market.Good g = s.isEmpty() ? null : Market.goodFor(s);
            if (g != null) v += g.value() * s.getCount();
        }
        return (int) Math.round(v);
    }

    /** Hand the gifts over: into the host's stores. */
    static void unloadGifts(ServerLevel level, VillageFolkEntity envoy, UUID host) {
        var inv = envoy.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || Market.goodFor(s) == null) continue;
            int move = s.getCount() - Caravans.carrierKeeps(envoy, s);
            if (move <= 0) continue;
            ItemStack left = Market.intoStores(level, host, s.copyWithCount(move));
            s.shrink(move - left.getCount());
        }
    }

    // ------------------------------------------------------------------ for the board, talk and gossip

    /** "Oakridge — allies, trading; Stonebrook — in a feud" */
    @Nullable
    public static String boardLine(UUID village) {
        List<String> parts = new ArrayList<>();
        for (Villages.Village o : Diplomacy.neighboursOf(village)) {
            if (!Ledger.knowEachOther(village, o.id())) continue;
            String s = Villages.name(o.id()) + " — " + Diplomacy.terms(village, o.id()).words;
            if (allied(village, o.id())) s += ", sworn allies";
            if (pact(village, o.id())) s += ", trading";
            parts.add(s);
            if (parts.size() >= 3) break;
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** The latest between this village and its neighbours, or null. */
    @Nullable
    public static String latest(UUID village) {
        return LATEST.get(village);
    }

    /** For the tests and /village relations. */
    public static String debug(UUID village) {
        StringBuilder sb = new StringBuilder("temper ").append(temper(village).name().toLowerCase(Locale.ROOT));
        for (Villages.Village o : Diplomacy.neighboursOf(village)) {
            sb.append("; ").append(Villages.name(o.id())).append(' ').append(Ledger.relation(village, o.id()));
            if (pact(village, o.id())) sb.append(" pact");
            if (allied(village, o.id())) sb.append(" allied");
        }
        List<UUID> w = WAITING.get(village);
        if (w != null && !w.isEmpty()) sb.append("; envoys waiting ").append(w.size());
        return sb.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
