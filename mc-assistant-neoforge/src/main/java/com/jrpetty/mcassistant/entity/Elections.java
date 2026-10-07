package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village chooses its leader.
 *
 * <p>Every {@link #TERM} days. Two days before, the ones the village thinks most of stand, each for
 * what it cares about most (Values): a full larder, a home for every family, the next age, safe
 * streets, good wages and trade, rest and merriment, or the old ways. They say so at the morning
 * assembly and to anybody who will listen, and the board in the square shows who stands and for
 * what.
 *
 * <p>On the day, each grown folk goes to the board at an hour of its own, when its work allows, and
 * casts its vote there: for whoever it judges best, weighing what it cares about against what the
 * village needs just now (hungry folk want the larder filled, folk sleeping on the ground want
 * homes, folk who saw the raiders want the watch), the people it loves and cannot stand, a nature
 * like its own, a hand that knows its trade, and how the one who led has done by them. It tells
 * whoever asks who it voted for and why.
 *
 * <p>In the evening the village gathers at the board, the votes are counted aloud, and the winner
 * is its leader until the next election. What it stood for is its mandate: a Provider's village
 * orders more hands to the fields, a Merchant's pays a little better, a Homemaker's builds houses
 * sooner (Orders, Leader, Villages); and at the next election it is judged on what it promised.
 * A leader who dies is followed by an election two days later, and someone speaks for the
 * village until then.
 */
public final class Elections {

    private Elections() {}

    /** Days from one election to the next. */
    public static final int TERM = 10;
    /** Days between the candidates standing and the vote. */
    static final int CALL = 2;
    /** The polls: open from the morning, closed before the count at dusk. */
    static final long OPEN = 1000L, CLOSE = 11900L;

    /** One who stands: who, for what (its own first care, or its second if another already stands for it), and its promise. */
    public record Candidate(UUID id, String name, Values.Value platform, Values.Value second, String pledge) {}

    /** An election under way. */
    static final class Campaign {
        final UUID village;
        final long called, voteDay;
        final List<Candidate> candidates = new ArrayList<>();
        /** Voter to the one it voted for, in the order the votes came in. */
        final Map<UUID, UUID> ballots = new LinkedHashMap<>();
        final Map<UUID, String> why = new HashMap<>();
        /** Who walked to the board to vote (the rest sent word, or voted late at the count). */
        final java.util.Set<UUID> atTheBoard = new java.util.HashSet<>();
        boolean counted;
        long announced = -1;

        Campaign(UUID village, long called, long voteDay) {
            this.village = village;
            this.called = called;
            this.voteDay = voteDay;
        }

        int votesFor(UUID candidate) {
            int n = 0;
            for (UUID c : ballots.values()) if (c.equals(candidate)) n++;
            return n;
        }
    }

    private static final Map<UUID, Campaign> NOW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CHECKED = new ConcurrentHashMap<>();
    /** Folk on their way to the board: when they set out (game time). */
    private static final Map<UUID, Long> WALKING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NOW.clear();
        CHECKED.clear();
        WALKING.clear();
    }

    // ------------------------------------------------------------------ the calendar

    /** Every few seconds for each village (from a folk's agenda): call the election when it is due, count it if the evening passed it by. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = CHECKED.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        CHECKED.put(id, now);
        if (!Government.holdsElections(id)) {                      // [identity] a lord's town, or the chaplain's: nobody is elected
            if (NOW.remove(id) != null) Ledger.note(id, "election.now", "");
            return;
        }
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        Campaign c = campaign(id);
        if (c == null) {
            if (voters(id).size() < 4) return;
            long next = nextVote(id, day);
            if (day >= next - CALL) call(level, v, day, Math.max(next, day + 1));
            return;
        }
        // The count is the evening's assembly; if it did not happen (nobody came, a bell rang),
        // the votes are counted quietly in the hall after dark.
        if (!c.counted && (day > c.voteDay || day == c.voteDay && t >= 13400L)) {
            count(level, v, day, null);
            return;
        }
        if (day == c.voteDay && c.announced != day && t >= 100L && t < 1300L) {
            c.announced = day;
            Market.assemblyNews(id, "Today we choose our " + title(id) + ". Standing: " + standing(c)
                + ". Go to the board in the square when your work allows, and cast your vote.");
        }
    }

    /** Is this the evening the votes are counted (Assemblies)? */
    public static boolean countsToday(UUID village, long day) {
        Campaign c = campaign(village);
        return c != null && !c.counted && c.voteDay == day;
    }

    /** The day of the next vote: six days after the founding for the first, then every TERM days. */
    static long nextVote(UUID village, long day) {
        String saved = Ledger.note(village, "election.next");
        if (saved != null && !saved.isEmpty()) {
            try {
                return Long.parseLong(saved);
            } catch (NumberFormatException ignored) { }
        }
        long founded = Math.max(0L, com.jrpetty.mcassistant.village.Chronicle.foundedOn(village));
        long first = Math.max(founded + 6L, day + CALL);
        Ledger.note(village, "election.next", Long.toString(first));
        return first;
    }

    /** A leader lost: someone speaks for the village, and an election follows in two days. */
    public static void vacancy(ServerLevel level, UUID village, String who, long day) {
        if (Government.vacancy(level, village, who, day)) return;      // [identity] a lord's heir, or the next chaplain, takes the seat
        Ledger.note(village, "elder", "");
        Ledger.note(village, "mandate", "");                       // what it was elected for goes with it
        Villages.Village v = Villages.get(village);
        Campaign c = campaign(village);
        if (v != null && (c == null || c.counted)) {
            Ledger.note(village, "election.next", Long.toString(day + CALL));
            Villages.tell(village, day, "with " + who + " gone, the village will choose a new " + title(village) + " in two days");
        }
    }

    // ------------------------------------------------------------------ standing

    /** The ones who stand, and the day of the vote. */
    static Campaign call(ServerLevel level, Villages.Village v, long day, long voteDay) {
        UUID id = v.id();
        Campaign c = new Campaign(id, day, voteDay);
        c.candidates.addAll(nominate(level, id, day));
        Hustings.enter(level, v, c, day);                              // [player-civic] the players standing, and a player who leads
        NOW.put(id, c);
        save(c);
        if (c.candidates.isEmpty()) return c;
        String when = voteDay - day <= 1 ? "tomorrow" : "in " + (voteDay - day) + " days";
        Villages.tell(id, day, "an election for " + title(id) + " was called for day " + voteDay + ": " + standing(c));
        Market.assemblyNews(id, "We choose our " + title(id) + " " + when + ". Standing: " + standing(c) + ".");
        for (Candidate k : c.candidates) {
            VillageFolkEntity f = loaded(id, k.id());
            if (f == null) continue;
            f.persona().remember(day, "I stood to be " + title(id) + " on day " + day + ", for " + k.platform().cares, 5);
            FolkTalk.speak(f, "I'm standing for " + title(id) + ": " + k.pledge() + "!");
        }
        return c;
    }

    /** Who stands: the ones the village thinks most of, the leader again if it will, each for something of its own. */
    static List<Candidate> nominate(ServerLevel level, UUID village, long day) {
        List<VillageFolkEntity> folk = voters(village);
        UUID leader = Villages.elder(village);
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        // A newcomer does not stand, unless there are hardly any others (a village just founded).
        int settled = 0;
        for (VillageFolkEntity c : folk) if (c.persona().since() < 0 || day - c.persona().since() >= 1) settled++;
        boolean newcomersToo = settled < 2;
        for (VillageFolkEntity c : folk) {
            long since = c.persona().since();
            boolean incumbent = c.getUUID().equals(leader);
            if (!incumbent && !newcomersToo && since >= 0 && day - since < 1) continue;
            int s = (int) Math.min(30, Math.max(0, day - since));
            for (VillageFolkEntity o : folk) if (o != c) s += o.life().affinity(c.getUUID()) / 2;
            s += Homeland.leaderFit(village, c);
            s += 2 * Math.min(20, c.veteranLevel());
            Social.Life l = c.life();
            if (l.has(Social.Trait.SOCIABLE)) s += 10;
            if (l.has(Social.Trait.HARDWORKING)) s += 6;
            if (l.has(Social.Trait.CURIOUS)) s += 4;
            if (l.has(Social.Trait.CHEERFUL)) s += 4;
            if (l.has(Social.Trait.GENEROUS)) s += 3;
            if (l.has(Social.Trait.SHY)) s -= 25;
            if (l.has(Social.Trait.EASYGOING)) s -= 6;
            if (incumbent) s += 25;
            score.put(c, s);
        }
        List<VillageFolkEntity> ranked = new ArrayList<>(score.keySet());
        ranked.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        int stand = folk.size() < 10 ? 2 : folk.size() < 30 ? 3 : 4;
        List<Candidate> out = new ArrayList<>();
        java.util.Set<Values.Value> taken = java.util.EnumSet.noneOf(Values.Value.class);
        for (VillageFolkEntity c : ranked) {
            if (out.size() >= stand) break;
            Values.Value p = Values.top(c), q = Values.second(c);
            if (taken.contains(p) && !taken.contains(q)) { Values.Value x = p; p = q; q = x; }
            taken.add(p);
            out.add(new Candidate(c.getUUID(), c.displayNameCap(), p, q, pledge(level, village, p)));
        }
        WarAndPeace.peaceCandidate(level, village, out, folk, stand, day);   // [war-peace] a town weary of its war puts up one for peace
        return out;
    }

    /** What it promises, for the village as it is: "a full larder: wider fields and more hands on the water". */
    static String pledge(ServerLevel level, UUID village, Values.Value p) {
        return switch (p) {
            case FOOD -> "a full larder: wider fields and more hands on the water";
            case HOMES -> "a home for every family, and the houses built first";
            case PROGRESS -> {
                Villages.Age age = Villages.ageOf(village);
                Villages.Age[] all = Villages.Age.values();
                String next = age.ordinal() + 1 < all.length ? all[age.ordinal() + 1].label : "great works";
                yield "on to " + next + ": more picks in the mine and the furnaces kept hot";
            }
            case SAFETY -> "safe streets: the watch walking every road, day and night";
            case WEALTH -> "better wages and a busy market";
            case LEISURE -> "rest days, feasts and a merrier town";
            case TRADITION -> "steady as we go: keep what works";
        };
    }

    // ------------------------------------------------------------------ voting

    /** How a folk judges one who stands, and the reason it would give. */
    record Judged(int score, String why) {}

    static Judged judge(ServerLevel level, VillageFolkEntity voter, Candidate c) {
        UUID village = voter.ownerId();
        if (voter.getUUID().equals(c.id())) return new Judged(100000, "I'm standing myself, and I mean what I said");
        int[] w = Values.of(voter);
        // What it stands for, against what this folk cares about.
        double cares = w[c.platform().ordinal()] + 0.4 * w[c.second().ordinal()];
        // What the village needs just now, as this folk feels it.
        int urg = village == null ? 0 : urgency(level, village, voter, c.platform());
        double needs = urg * (0.5 + w[c.platform().ordinal()] / 100.0);
        // The people.
        double people = voter.life().affinity(c.id()) * 0.5;
        boolean partner = c.id().equals(voter.life().partner());
        VillageFolkEntity cf = village == null ? null : loaded(village, c.id());
        boolean family = cf != null && (voter.life().parents().contains(c.name()) || cf.life().parents().contains(voter.displayNameCap()));
        if (partner) people += 40;
        if (family) people += 20;
        // A nature like its own, and a hand that knows its trade.
        double nature = 0, skill = 0;
        if (cf != null) {
            for (Social.Trait t : voter.life().traits()) {
                if (cf.life().has(t)) nature += 8;
                if (t.opposite() != null && cf.life().has(t.opposite())) nature -= 8;
            }
            skill = Math.min(25, cf.veteranLevel()) * 0.6;
        }
        // The one who led: how it has done by them.
        double record = 0;
        if (village != null && c.id().equals(Villages.elder(village))) {
            record = record(level, village);
            if (Values.top(voter) == Values.Value.TRADITION) record += 10;
        }
        long day = level.getDayTime() / 24000L;
        double mind = Math.floorMod(Objects.hash(voter.getUUID(), c.id(), day), 7);
        double total = cares + needs + people + nature + skill + record + mind;
        total += WarAndPeace.electionLean(level, voter, c);           // [war-peace] the war: weary for peace, or a war going well
        total += Hustings.lean(level, voter, c);                       // [player-civic] the campaign; a player's liking and record
        // The reason it gives: whichever weighed most.
        String why = "they stand for " + c.platform().cares + ", and so do I";
        double most = cares * 0.6;
        if (needs > most) { most = needs; why = urgentWhy(c.platform()); }
        if (people > most) { most = people; why = partner ? "they're my partner" : family ? "family stands by family" : "they're a good friend to me"; }
        if (record > most) { most = record; why = "they've done well by us"; }
        if (skill > most) { most = skill; why = "they know what they're doing"; }
        if (nature > most) { why = "they're my kind of folk"; }
        why = Hustings.why(voter, c, why);                             // [player-civic] a player's own doing
        return new Judged((int) Math.round(total), why);
    }

    /** How pressing what it stands for is just now, 0 to 40ish, as this folk feels it. */
    static int urgency(ServerLevel level, UUID village, VillageFolkEntity voter, Values.Value p) {
        Contentment.View view = Contentment.of(level, village);
        switch (p) {
            case FOOD -> {
                Leader.Plan plan = Leader.plan(village);
                int u = plan == Leader.Plan.FAMINE ? 40 : plan == Leader.Plan.SHORT ? 20 : 0;
                if (voter.countFood() == 0) u += 10;
                return u;
            }
            case HOMES -> {
                int adults = 0, bedless = 0;
                for (AssistantEntity a : Villages.folkOf(village)) {
                    if (a.isBaby()) continue;
                    adults++;
                    if (a.bedPos() == null) bedless++;
                }
                int u = adults == 0 ? 0 : 40 * bedless / adults;
                if (voter.bedPos() == null) u += 15;
                return u;
            }
            case SAFETY -> {
                int u = Raids.underAlarm(village) ? 20 : 0;
                if (view != null) u += Math.max(0, 6 - view.safety()) * 4;
                if (day(level) - voter.persona().hurtDay <= 2) u += 10;
                return u;
            }
            case PROGRESS -> {
                long inAge = day(level) - Math.max(0, Villages.agedOn(village));
                return (int) Math.max(0, Math.min(25, (inAge - 6) * 2));
            }
            case WEALTH -> {
                int u = day(level) - voter.shortPaidDay <= 2 ? 15 : 0;
                if (Wealth.tier(voter) == Wealth.Tier.POOR) u += 10;
                return u;
            }
            case LEISURE -> {
                int content = Contentment.score(village);
                return content < 50 ? (50 - content) / 2 : 0;
            }
            case TRADITION -> {
                return Contentment.score(village) >= 65 ? 10 : 0;
            }
        }
        return 0;
    }

    static String urgentWhy(Values.Value p) {
        return switch (p) {
            case FOOD -> "we're short of food, and they'll see us fed";
            case HOMES -> "too many of us sleep on the ground, and they'll build us homes";
            case SAFETY -> "it isn't safe out there, and they'll keep the watch";
            case PROGRESS -> "we've stood still too long, and they'll take us on";
            case WEALTH -> "the wages are thin, and they'll mend them";
            case LEISURE -> "we're worn out and fed up, and they'll give us a rest";
            case TRADITION -> "things are going well, so why change them";
        };
    }

    /** A folk casts its vote, here at the board or from wherever it is. */
    static void cast(ServerLevel level, Campaign c, VillageFolkEntity voter, boolean atTheBoard) {
        if (c.ballots.containsKey(voter.getUUID()) || c.candidates.isEmpty()) return;
        Candidate best = null;
        Judged bj = null;
        for (Candidate k : c.candidates) {
            Judged j = judge(level, voter, k);
            if (bj == null || j.score() > bj.score()) { best = k; bj = j; }
        }
        c.ballots.put(voter.getUUID(), best.id());
        c.why.put(voter.getUUID(), bj.why());
        if (atTheBoard) c.atTheBoard.add(voter.getUUID());
        save(c);
        long day = day(level);
        voter.persona().remember(day, "I voted for " + (best.id().equals(voter.getUUID()) ? "myself" : best.name())
            + " on day " + day + ": " + bj.why(), 2);
        if (!atTheBoard) return;
        voter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, voter.getX(), voter.getY() + 2.1, voter.getZ(), 6, 0.3, 0.2, 0.3, 0.02);
        level.playSound(null, voter.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.8F, 1.0F);
        if (!best.id().equals(voter.getUUID())) {
            FolkTalk.speak(voter, FolkTalk.pick(level.getRandom(), "My vote's for ", "I'm voting ", "There. ")
                + best.name() + " — " + bj.why() + ".");
        } else {
            FolkTalk.speak(voter, "A vote for myself, and no shame in it.");
        }
    }

    /** Is this folk's own hour to vote come, with its ballot not yet cast? (The leader's escort
     *  leaves the leader's side for that much: Patrols.) */
    public static boolean dueToVote(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !f.persona().rolled()) return false;
        Campaign c = NOW.get(id);
        if (c == null || c.counted || c.candidates.isEmpty()) return false;
        long dayTime = level.getDayTime(), day = dayTime / 24000L, t = dayTime % 24000L;
        if (day != c.voteDay || t < OPEN || t >= CLOSE || c.ballots.containsKey(f.getUUID())) return false;
        return t >= OPEN + Math.floorMod(f.getUUID().getLeastSignificantBits(), CLOSE - OPEN - 2500L);
    }

    /**
     * Every few ticks for each folk (VillageFolkEntity): on the day of the vote, at its own hour and
     * when its work allows, it walks to the board and votes. True while it is about it.
     */
    public static boolean goVote(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !f.persona().rolled()) return false;
        Campaign c = NOW.get(id);
        if (c == null || c.counted || c.candidates.isEmpty()) return false;
        long dayTime = level.getDayTime(), day = dayTime / 24000L, t = dayTime % 24000L, now = level.getGameTime();
        if (day != c.voteDay || t < OPEN || t >= CLOSE || c.ballots.containsKey(f.getUUID())) {
            WALKING.remove(f.getUUID());
            return false;
        }
        if (f.isSleeping() || f.isHired() || f.getTarget() != null || Raids.underAlarm(id) || Assemblies.attending(f)
                || Nether.away(f) || Drover.busy(f) || Scouts.out(f) || f.trip() != null) {
            return false;
        }
        Long since = WALKING.get(f.getUUID());
        if (since == null) {
            // Its own hour, so the village does not come all at once; and not in the middle of a job
            // unless the day is getting on.
            long turn = OPEN + Math.floorMod(f.getUUID().getLeastSignificantBits(), CLOSE - OPEN - 2500L);
            if (t < turn) return false;
            if (f.peekJob() != null && t < Math.min(CLOSE - 1500L, turn + 2500L)) return false;
            f.clearQueue();
            f.getNavigation().stop();
            WALKING.put(f.getUUID(), now);
            since = now;
            if (level.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Off to vote.", "Time I cast my vote.", "Back soon: the vote."));
        }
        Villages.Village v = Villages.get(id);
        BlockPos board = VillageBoards.lectern(id);
        if (board == null) board = v != null ? v.centre() : f.blockPosition();
        double d2 = f.blockPosition().distSqr(board);
        long gone = now - since;
        if (d2 <= 2.5 * 2.5 || gone > 1600L && d2 <= 10 * 10) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(board.getX() + 0.5, board.getY() + 1.5, board.getZ() + 0.5);
            cast(level, c, f, true);
            WALKING.remove(f.getUUID());
            f.hobbyNow = "voted";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        if (gone > 3600L) {                                        // could not get there: sends its vote with a neighbour
            cast(level, c, f, false);
            WALKING.remove(f.getUUID());
            return false;
        }
        if (f.getNavigation().isDone() || gone % 60L == 0) f.walkTo(board, 0.9D);
        f.hobbyNow = "on the way to vote";
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    // ------------------------------------------------------------------ the count

    /** The result of a count: the winner (or null), and the votes, most first. */
    public record Result(@Nullable Candidate winner, LinkedHashMap<Candidate, Integer> votes, int voted, int voters) {}

    /**
     * Count the votes: those who never got to the board but are about the village vote now, as
     * they stand. The winner leads; it, and the ones it beat, remember the day. {@code speech}: the
     * lines to say it aloud with, or null to count it quietly.
     */
    static Result count(ServerLevel level, Villages.Village v, long day, @Nullable List<Assemblies.Line> speech) {
        UUID id = v.id();
        Campaign c = campaign(id);
        if (c == null) {
            // Not called (a test, or a village that never got round to it): stand and vote at once.
            c = new Campaign(id, day, day);
            c.candidates.addAll(nominate(level, id, day));
            NOW.put(id, c);
        }
        List<VillageFolkEntity> voters = voters(id);
        for (VillageFolkEntity f : voters) {
            if (!c.ballots.containsKey(f.getUUID()) && f.blockPosition().distSqr(v.centre()) < 96 * 96) cast(level, c, f, false);
        }
        LinkedHashMap<Candidate, Integer> votes = new LinkedHashMap<>();
        List<Candidate> order = new ArrayList<>(c.candidates);
        UUID leader = Villages.elder(id);
        final Campaign fc = c;
        order.sort((a, b) -> {
            int va = fc.votesFor(a.id()), vb = fc.votesFor(b.id());
            if (va != vb) return Integer.compare(vb, va);
            if (a.id().equals(leader)) return -1;                     // a tie keeps the one in office
            if (b.id().equals(leader)) return 1;
            return 0;
        });
        for (Candidate k : order) votes.put(k, c.votesFor(k.id()));
        Candidate winner = order.isEmpty() ? null : order.get(0);
        Result r = new Result(winner, votes, c.ballots.size(), voters.size());
        c.counted = true;
        save(c);
        NOW.remove(id);
        Ledger.note(id, "election.now", "");
        Ledger.note(id, "election.next", Long.toString(day + Government.term(id, TERM)));   // [identity] a commune's seven, the elders' fourteen
        String tally = tally(r);
        Ledger.note(id, "election.last", day + "|" + (winner == null ? "" : winner.name()) + "|" + tally);
        if (winner == null) return r;
        if (speech == null) {
            install(level, v, winner, day);
            Villages.tell(id, day, winner.name() + " was elected " + title(id) + " for " + winner.platform().cares + " (" + tally + ")");
        }
        return r;
    }

    /** "Bryn 12, Fen 8 (20 of 23 voted)". */
    static String tally(Result r) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Candidate, Integer> e : r.votes().entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey().name()).append(' ').append(e.getValue());
        }
        return sb + " (" + r.voted() + " of " + r.voters() + " voted)";
    }

    /** The winner takes office: the elder, its mandate, and what it promised written down to be judged by. */
    static void install(ServerLevel level, Villages.Village v, Candidate winner, long day) {
        UUID id = v.id();
        VillageFolkEntity f = loaded(id, winner.id());
        Villages.electElder(id, winner.id(), winner.name(), day, f);
        Ledger.note(id, "mandate", winner.id() + "|" + winner.platform().name() + "|" + day + "|" + snapshot(level, id));
        if (f != null) {
            f.persona().remember(day, "the village elected me " + title(id) + " for " + winner.platform().cares, 9);
        }
        WarAndPeace.elected(level, id, winner.id(), day);              // [war-peace] a peace candidate elected sues for peace
        Hustings.installed(level, v, winner, day);                     // [player-civic] a player in office, or a player who lost
    }

    /** What the losers say: in their own way. */
    static String concede(VillageFolkEntity loser, String winner) {
        Social.Life l = loser.life();
        if (l.has(Social.Trait.GENEROUS) || l.has(Social.Trait.CHEERFUL)) return "Well done, " + winner + ". You'll have my help.";
        if (l.has(Social.Trait.GRUMPY)) return "Hmph. We'll see how long that lasts.";
        if (l.has(Social.Trait.SHY)) return "Congratulations.";
        if (l.has(Social.Trait.HARDWORKING)) return "Fair enough. Back to work, then.";
        return "The village has spoken. Good luck, " + winner + ".";
    }

    /**
     * The evening assembly's lines (Assemblies): the ones who stand speak, the votes are counted and
     * the winner announced, and they all answer it in their own way.
     */
    static void script(ServerLevel level, UUID village, List<Assemblies.Line> s, RandomSource r) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        long day = day(level);
        Campaign c = campaign(village);
        if (c == null || c.candidates.isEmpty()) {
            c = new Campaign(village, day, day);
            c.candidates.addAll(nominate(level, village, day));
            NOW.put(village, c);
            save(c);
        }
        if (c.candidates.isEmpty()) return;
        String title = title(village);
        s.add(new Assemblies.Line(null, "It is time to choose our " + title + ".", '?', null));
        for (Candidate k : c.candidates) {
            s.add(new Assemblies.Line(k.id(), "Choose me, and I'll give you " + k.pledge() + ".", '!', null));
        }
        Result res = count(level, v, day, s);
        if (res.winner() == null) return;
        Candidate win = res.winner();
        s.add(new Assemblies.Line(null, res.voted() + " of " + res.voters() + " have voted. The count: " + tally(res).replaceAll(" \\(.*\\)$", "") + ".", '?', null));
        s.add(new Assemblies.Line(null, win.name() + " is our " + title + ", for " + win.platform().cares + "!", '!', () -> {
            install(level, v, win, day);
            Villages.tell(village, day, win.name() + " was elected " + title + " for " + win.platform().cares + " (" + tally(res) + ")");
        }));
        s.add(new Assemblies.Line(win.id(), FolkTalk.pick(r, "Thank you. I'll keep my word.", "I won't let you down.",
            "Thank you all. Now, to work!"), '!', null));
        int said = 0;
        for (Candidate k : res.votes().keySet()) {
            if (k == win || said >= 2) continue;
            VillageFolkEntity loser = loaded(village, k.id());
            if (loser == null) continue;
            s.add(new Assemblies.Line(k.id(), concede(loser, win.name()), ' ', null));
            loser.persona().remember(day, "I lost the election to " + win.name() + " on day " + day, 3);
            said++;
        }
    }

    // ------------------------------------------------------------------ the mandate and the record

    /** What the leader in office was elected for, or null. */
    @Nullable
    public static Values.Value mandate(UUID village) {
        String m = Ledger.note(village, "mandate");
        if (m == null || m.isEmpty()) return null;
        String[] p = m.split("\\|");
        if (p.length < 2) return null;
        UUID leader = Villages.elder(village);
        if (leader == null || !leader.toString().equals(p[0])) return null;      // only while the one elected leads
        try {
            return Values.Value.valueOf(p[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The order a mandate asks for most (Orders.choose). */
    @Nullable
    static Orders.Order orderFor(@Nullable Values.Value m) {
        if (m == null) return null;
        return switch (m) {
            case FOOD -> Orders.Order.LARDER;
            case HOMES -> Orders.Order.TIMBER;
            case PROGRESS -> Orders.Order.DIG;
            case SAFETY -> Orders.Order.WATCH;
            case WEALTH -> Orders.Order.MARKET;
            case LEISURE, TRADITION -> Orders.Order.STEADY;
        };
    }

    /** The village in numbers, to judge a leader by: food days, beds, age, buildings, contentment, treasury. */
    static String snapshot(ServerLevel level, UUID village) {
        Leader.Books b = Leader.books(village);
        int adults = 0, bedded = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby()) continue;
            adults++;
            if (a.bedPos() != null) bedded++;
        }
        return String.format(Locale.ROOT, "%.1f|%d|%d|%d|%d|%d",
            b == null ? 0.0 : b.days(), adults == 0 ? 100 : 100 * bedded / adults, Villages.ageOf(village).ordinal(),
            Ledger.buildings(village).size(), Contentment.score(village), Ledger.coins(village));
    }

    /** How the one in office has done by the village, from -20 to 20: on what it promised above all. */
    static int record(ServerLevel level, UUID village) {
        String m = Ledger.note(village, "mandate");
        if (m == null || m.isEmpty()) return 0;
        String[] p = m.split("\\|");
        if (p.length < 9) return 0;
        String[] now = snapshot(level, village).split("\\|");
        try {
            double foodThen = Double.parseDouble(p[3]), foodNow = Double.parseDouble(now[0]);
            int bedThen = Integer.parseInt(p[4]), bedNow = Integer.parseInt(now[1]);
            int ageThen = Integer.parseInt(p[5]), ageNow = Integer.parseInt(now[2]);
            int builtThen = Integer.parseInt(p[6]), builtNow = Integer.parseInt(now[3]);
            int contentThen = Integer.parseInt(p[7]), contentNow = Integer.parseInt(now[4]);
            int coinsThen = Integer.parseInt(p[8]), coinsNow = Integer.parseInt(now[5]);
            int score = (contentNow - contentThen) / 4 + (contentNow >= 70 ? 4 : contentNow < 35 ? -6 : 0);
            Values.Value promised = Values.Value.valueOf(p[1]);
            score += switch (promised) {
                case FOOD -> foodNow >= Math.max(2.0, foodThen) ? 10 : foodNow < 1.0 ? -12 : 0;
                case HOMES -> bedNow >= Math.max(90, bedThen + 10) ? 10 : bedNow < bedThen ? -10 : 0;
                case PROGRESS -> ageNow > ageThen ? 14 : builtNow - builtThen >= 2 ? 6 : -6;
                case SAFETY -> Raids.underAlarm(village) ? -8 : 6;
                case WEALTH -> coinsNow > coinsThen ? 8 : -6;
                case LEISURE -> contentNow > contentThen ? 8 : -4;
                case TRADITION -> Math.abs(contentNow - contentThen) <= 10 ? 6 : -4;
            };
            return Math.max(-20, Math.min(20, score));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** For talk: has the one in office kept its word? */
    @Nullable
    static String kept(ServerLevel level, UUID village) {
        Values.Value m = mandate(village);
        if (m == null) return null;
        int rec = record(level, village);
        String who = Villages.elderName(village);
        if (rec >= 8) return who + " promised us " + m.cares + ", and has kept their word";
        if (rec <= -8) return who + " promised us " + m.cares + ", and we're still waiting";
        return who + " promised us " + m.cares + "; so far, so-so";
    }

    // ------------------------------------------------------------------ for the board, the status and talk

    /** The board's lines on the election (VillageBoards): who stands, the vote under way, or the last result. */
    public static List<String> board(UUID village, long day) {
        if (!Government.holdsElections(village)) return Government.board(village);   // [identity] a lord or the chaplain
        List<String> out = new ArrayList<>();
        Campaign c = campaign(village);
        if (c != null && !c.counted && !c.candidates.isEmpty()) {
            boolean today = c.voteDay == day;
            out.add(today ? "RG|ELECTION TODAY: vote here at the board! " + c.ballots.size() + " of " + voters(village).size() + " have voted."
                : "RG|Election on day " + c.voteDay + ": choosing our " + title(village) + ".");
            for (Candidate k : c.candidates) {
                out.add("RN|" + k.name() + " (" + k.platform().type + "): " + k.pledge() + (today ? " — " + c.votesFor(k.id()) + " so far" : "") + ".");
            }
            return out;
        }
        String last = Ledger.note(village, "election.last");
        if (last != null && !last.isEmpty()) {
            String[] p = last.split("\\|", 3);
            if (p.length == 3) out.add("RM|Elected on day " + p[0] + ": " + p[1] + " — " + p[2] + ". Next election: day " + nextVote(village, day) + ".");
        } else {
            out.add("RM|First election: day " + nextVote(village, day) + ".");
        }
        return out;
    }

    /** For the status: "next on day 24; Bryn and Fen stand" / "voting today, 8 of 20" / "last: …". */
    public static String line(UUID village, long day) {
        Campaign c = campaign(village);
        Values.Value m = mandate(village);
        String chosen = Ledger.note(village, "elder");
        String mandate = m != null ? "; " + Villages.elderName(village) + " leads for " + m.cares
            : (chosen == null || chosen.isEmpty()) && c != null && !c.counted && !Villages.elderName(village).isEmpty()
                ? "; " + Villages.elderName(village) + " stands in until the count" : "";
        if (c != null && !c.counted && !c.candidates.isEmpty()) {
            StringBuilder sb = new StringBuilder(c.voteDay == day ? "voting today, " + c.ballots.size() + " of " + voters(village).size()
                + " voted (" + c.atTheBoard.size() + " at the board)" : "on day " + c.voteDay);
            sb.append("; standing: ");
            for (int i = 0; i < c.candidates.size(); i++) {
                Candidate k = c.candidates.get(i);
                if (i > 0) sb.append(", ");
                sb.append(k.name()).append(" the ").append(k.platform().type).append(" (").append(c.votesFor(k.id())).append(')');
            }
            return sb + mandate;
        }
        String last = Ledger.note(village, "election.last");
        String l = last == null || last.isEmpty() ? "none yet" : "last on day " + last.replaceFirst("\\|", ", ").replaceFirst("\\|", " — ");
        return l + "; next on day " + nextVote(village, day) + mandate;
    }

    /** What a folk says when asked about the election. */
    public static String talk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "";
        Campaign c = campaign(village);
        String me = "Me, I'm " + Values.describe(f) + ".";
        if (c != null && !c.counted && !c.candidates.isEmpty()) {
            UUID voted = c.ballots.get(f.getUUID());
            if (voted != null) {
                Candidate k = find(c, voted);
                return "I voted for " + (voted.equals(f.getUUID()) ? "myself" : k == null ? "the one I trust" : k.name())
                    + " — " + c.why.getOrDefault(f.getUUID(), "it seemed right") + ". " + me;
            }
            Candidate best = null;
            Judged bj = null;
            for (Candidate k : c.candidates) {
                Judged j = judge(level, f, k);
                if (bj == null || j.score() > bj.score()) { best = k; bj = j; }
            }
            String when = c.voteDay == day(level) ? "today" : "on day " + c.voteDay;
            return "We vote " + when + ". I'm minded to vote for " + (best.id().equals(f.getUUID()) ? "myself" : best.name())
                + " — " + bj.why() + ". " + me;
        }
        String kept = kept(level, village);
        return (kept == null ? "The next election is on day " + nextVote(village, day(level)) + "." : kept + ". The next election is on day "
            + nextVote(village, day(level)) + ".") + " " + me;
    }

    /** A line of gossip about the election (Smalltalk), or null. */
    @Nullable
    public static String[] gossip(VillageFolkEntity a, VillageFolkEntity b) {
        UUID village = a.ownerId();
        if (village == null || !(a.level() instanceof ServerLevel level)) return null;
        Campaign c = campaign(village);
        if (c == null || c.counted || c.candidates.isEmpty()) return null;
        Candidate mine = null, theirs = null;
        Judged jm = null, jt = null;
        for (Candidate k : c.candidates) {
            Judged x = judge(level, a, k), y = judge(level, b, k);
            if (jm == null || x.score() > jm.score()) { mine = k; jm = x; }
            if (jt == null || y.score() > jt.score()) { theirs = k; jt = y; }
        }
        String open = "Who are you voting for?";
        String answer = (theirs.id().equals(b.getUUID()) ? "Myself, of course!" : theirs.name() + ". " + capital(jt.why()) + ".");
        String last = mine.id().equals(theirs.id()) ? "Same here." : "Not me. " + (mine.id().equals(a.getUUID()) ? "I'm standing!" : mine.name() + ": " + jm.why() + ".");
        return new String[]{ open, answer, last };
    }

    // ------------------------------------------------------------------ helpers and keeping

    /** "thane", "mayor", "elder": what the land calls its leader. */
    static String title(UUID village) {
        return Government.title(village);                              // [identity] the land's title, or the guildmaster, the steward...
    }

    static String standing(Campaign c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.candidates.size(); i++) {
            Candidate k = c.candidates.get(i);
            if (i > 0) sb.append(i == c.candidates.size() - 1 ? " and " : ", ");
            sb.append(k.name()).append(", for ").append(k.platform().cares);
        }
        return sb.toString();
    }

    @Nullable
    static Candidate find(Campaign c, UUID id) {
        for (Candidate k : c.candidates) if (k.id().equals(id)) return k;
        return null;
    }

    /** The grown folk of the village who may vote. */
    static List<VillageFolkEntity> voters(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase() && f.persona().rolled()) out.add(f);
        }
        return Government.voters(village, out);                        // [identity] the elders, the masters, the householders
    }

    @Nullable
    static VillageFolkEntity loaded(UUID village, UUID who) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(who)) return f;
        return null;
    }

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The election under way, from memory or (after a restart) from the Ledger. */
    @Nullable
    static Campaign campaign(UUID village) {
        Campaign c = NOW.get(village);
        if (c != null) return c;
        String saved = Ledger.note(village, "election.now");
        if (saved == null || saved.isEmpty()) return null;
        try {
            String[] p = saved.split("\\|", -1);
            c = new Campaign(village, Long.parseLong(p[0]), Long.parseLong(p[1]));
            if (!p[2].isEmpty()) {
                for (String k : p[2].split(";")) {
                    String[] q = k.split("~", -1);
                    c.candidates.add(new Candidate(UUID.fromString(q[0]), q[1], Values.Value.valueOf(q[2]), Values.Value.valueOf(q[3]), q[4]));
                }
            }
            if (p.length > 3 && !p[3].isEmpty()) {
                for (String b : p[3].split(",")) {
                    String[] q = b.split(">");
                    c.ballots.put(UUID.fromString(q[0]), UUID.fromString(q[1]));
                }
            }
            c.counted = p.length > 4 && "1".equals(p[4]);
            if (c.counted) return null;
            NOW.put(village, c);
            return c;
        } catch (RuntimeException e) {
            Ledger.note(village, "election.now", "");
            return null;
        }
    }

    static void save(Campaign c) {
        StringBuilder cands = new StringBuilder();
        for (Candidate k : c.candidates) {
            if (cands.length() > 0) cands.append(';');
            cands.append(k.id()).append('~').append(k.name().replace("~", "-")).append('~').append(k.platform().name())
                .append('~').append(k.second().name()).append('~').append(k.pledge().replace("~", "-").replace("|", "/").replace(";", ","));
        }
        StringBuilder ballots = new StringBuilder();
        for (Map.Entry<UUID, UUID> e : c.ballots.entrySet()) {
            if (ballots.length() > 0) ballots.append(',');
            ballots.append(e.getKey()).append('>').append(e.getValue());
        }
        Ledger.note(c.village, "election.now", c.called + "|" + c.voteDay + "|" + cands + "|" + ballots + "|" + (c.counted ? "1" : "0"));
    }

    // ------------------------------------------------------------------ tests

    /** Tests: call an election now, the vote tomorrow (or today). */
    public static int callForTests(ServerLevel level, Villages.Village v, long voteDay) {
        Campaign c = call(level, v, day(level), voteDay);
        return c.candidates.size();
    }

    /** Tests: who stands, as "Name:PLATFORM". */
    public static List<String> standingForTests(UUID village) {
        List<String> out = new ArrayList<>();
        Campaign c = campaign(village);
        if (c != null) for (Candidate k : c.candidates) out.add(k.name() + ":" + k.platform().name());
        return out;
    }

    /** Tests: has this folk voted, and did it walk to the board to do it? {voted, atTheBoard}. */
    public static boolean[] votedForTests(UUID village, UUID folk) {
        Campaign c = campaign(village);
        return c == null ? new boolean[]{ false, false } : new boolean[]{ c.ballots.containsKey(folk), c.atTheBoard.contains(folk) };
    }

    /** Tests: count now, quietly. */
    public static Result countForTests(ServerLevel level, Villages.Village v) {
        return count(level, v, day(level), null);
    }

    /** Tests: which of these two would a folk vote for (their ids), and why. */
    public static String preferForTests(ServerLevel level, VillageFolkEntity voter, UUID a, Values.Value pa, UUID b, Values.Value pb) {
        Candidate ca = new Candidate(a, "A", pa, pa, pledge(level, voter.ownerId(), pa));
        Candidate cb = new Candidate(b, "B", pb, pb, pledge(level, voter.ownerId(), pb));
        Judged ja = judge(level, voter, ca), jb = judge(level, voter, cb);
        return (ja.score() >= jb.score() ? "A" : "B") + "|" + ja.score() + "|" + jb.score() + "|" + (ja.score() >= jb.score() ? ja.why() : jb.why());
    }
}
