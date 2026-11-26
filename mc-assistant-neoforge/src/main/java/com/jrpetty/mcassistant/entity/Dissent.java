package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BannerBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featcivic] The town's quarrel with how it is governed: an unrest meter, nought to a hundred, and what it comes to.
 *
 * <p><b>What feeds it.</b> Each grown folk's grievance, looked at each morning: how the town is doing by it (its
 * contentment), how far the form of government is from the one its own cares and nature would choose (an individualist
 * Merchant in a planned state, a Free Spirit under a curfew, an egalitarian under a lord, a worldly soul under the
 * chaplain, a journeyman with no vote in a guild republic), the taxes (TaxUnrest), and talk from abroad (Ideology's
 * pamphlets). The town's level moves toward the average, faster where nobody can vote the government out: a democracy
 * lets it out at the polls (half the pressure, and every count lets off more: {@link #letOut}); a lordship, a planned
 * state and military rule bottle it up and it builds the faster.
 *
 * <p><b>What it comes to.</b> Past 25 folk grumble. Past 45 a petition goes round: the aggrieved walk to the board and sign
 * it, and once a third of the town has signed it is put to the leader, who gives way (a tax cut, a hated law undone) or
 * does not. Past 60, a protest in the square: banners in hand, chants, a ringleader's case and the leader's answer (a
 * general's answer is the watch). Past 75, where the government cannot be voted out and has refused the town, a strike:
 * the aggrieved down tools and picket the hall. Past 90 two mornings running, the rising: the town gathers at the hall,
 * the old order's posters come down, its flag with them, the old leader leaves town, and the town takes up the government
 * it wanted (Government.set). Where the watch is strong, the town martial and the government weak, the watch may move
 * first: a coup, and military rule.
 *
 * <p>A healthy democracy rarely gets past a petition: its level is capped short of a strike, and a protest there calls an
 * early election instead.
 */
public final class Dissent {

    private Dissent() {}

    public enum Stage {
        CALM("calm"), GRUMBLING("grumbling"), PETITION("a petition going round"), PROTEST("protests in the square"),
        STRIKE("strikes"), UPRISING("on the edge of revolt");

        public final String words;
        Stage(String words) { this.words = words; }
    }

    public static final int GRUMBLE = 25, PETITION = 45, PROTEST = 60, STRIKE = 75, UPRISING = 90;
    /** The afternoon's window for a protest or a rising. */
    static final long FROM = 8000L, TO = 11500L;

    /** Banners handed out at a protest (folk to town), to be had back. */
    private static final Map<UUID, UUID> BANNERS = new ConcurrentHashMap<>();
    /** Folk on their way to sign the petition: since when. */
    private static final Map<UUID, Long> SIGNING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BANNERS.clear();
        SIGNING.clear();
    }

    // ------------------------------------------------------------------ reading it

    public static int level(@Nullable UUID village) {
        return village == null ? 0 : Math.max(0, Math.min(100, ni(village, "dissent.level")));
    }

    public static Stage stage(@Nullable UUID village) {
        int l = level(village);
        return l >= UPRISING ? Stage.UPRISING : l >= STRIKE ? Stage.STRIKE : l >= PROTEST ? Stage.PROTEST : l >= PETITION ? Stage.PETITION
            : l >= GRUMBLE ? Stage.GRUMBLING : Stage.CALM;
    }

    /** How fast the town's grievances turn into unrest under each form: half in a democracy, a third again under a general. */
    public static double amplifier(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> 0.5;
            case REEVE -> 0.6;
            case COMMUNE -> 0.75;
            case COUNCIL, GUILD -> 0.85;
            case CHAPLAIN -> 1.0;
            case LORD -> 1.15;
            case PLANNED -> 1.25;
            case JUNTA -> 1.35;
        };
    }

    /** The most it may reach under a form: an elected democracy never gets as far as a strike. */
    static int cap(Government.Form f) {
        return FormChoice.democratic(f) || f == Government.Form.COMMUNE ? STRIKE - 1 : 100;
    }

    // ------------------------------------------------------------------ who is aggrieved, and why

    /** One folk's grievance with how it is governed, nought to a hundred, and its chief cause. */
    record Grievance(int score, String why) {}

    static Grievance grievance(UUID village, VillageFolkEntity f, Government.Form form) {
        if (f.isBaby()) return new Grievance(0, "");
        double g = 0;
        String why = "";
        double most = 0;
        int content = Contentment.score(village);
        double low = Math.max(0, 55 - content) * 0.6;
        g += low;
        if (low > most) { most = low; why = "the town is not doing well"; }
        // How far the form is from the one this folk's cares and nature would choose.
        int[] blend = blend(village, f);
        double bestFit = Double.NEGATIVE_INFINITY;
        for (Government.Form x : Government.Form.values()) bestFit = Math.max(bestFit, FormChoice.fit(x, blend));
        double misfit = Math.max(0, Math.min(40, bestFit - FormChoice.fit(form, blend))) * 0.55;
        g += misfit;
        if (misfit > most) { most = misfit; why = "they would rather be ruled another way"; }
        // The freedoms.
        int[] w = Values.of(f);
        Social.Life life = f.life();
        double free = 0;
        String freeWhy = "";
        switch (form) {
            case PLANNED -> {
                free = w[Values.Value.WEALTH.ordinal()] * 0.35 + (life.has(Social.Trait.SOCIABLE) || life.has(Social.Trait.CURIOUS) ? 6 : 0)
                    + (life.has(Social.Trait.HARDWORKING) ? 4 : 0);
                freeWhy = w[Values.Value.WEALTH.ordinal()] >= 40 ? "no business of their own allowed" : "the committee decides everything";
            }
            case JUNTA -> {
                free = f.stationTask() == AssistantEntity.StationTask.GUARD ? -20 : w[Values.Value.LEISURE.ordinal()] * 0.3 + 6;
                freeWhy = "the curfew and the checks";
            }
            case LORD -> { free = Math.max(0, Ethos.folkLean(f)[Ethos.Axis.RANK.ordinal()]) * 0.35; freeWhy = "a lord over them"; }
            case CHAPLAIN -> { free = Math.max(0, -Ethos.folkLean(f)[Ethos.Axis.FAITH.ordinal()]) * 0.35; freeWhy = "the chapel's rules"; }
            case GUILD -> { free = f.veteranLevel() < 10 ? 8 : 0; freeWhy = "no vote for those outside the guild"; }
            case COUNCIL -> { free = f.ageYears() < 30 ? 6 : 0; freeWhy = "no say for the young"; }
            default -> { }
        }
        g += free;
        if (free > most) { most = free; why = freeWhy; }
        // The taxes and the laws.
        double tax = TaxUnrest.level(village) * 0.25;
        g += tax;
        if (tax > most) { most = tax; why = "the taxes"; }
        if (LawBook.curfew(village) && Values.top(f) == Values.Value.LEISURE) { g += 5; if (5 > most) { most = 5; why = "the curfew"; } }
        // Talk from abroad.
        int stirred = stirred(village, f.level().getDayTime() / 24000L);
        if (stirred > 0) { g += stirred; if (stirred > most) { most = stirred; why = "talk from " + stirredBy(village); } }
        // Its temper.
        if (life.has(Social.Trait.GRUMPY)) g += 6;
        if (life.has(Social.Trait.EASYGOING)) g -= 6;
        if (life.has(Social.Trait.CHEERFUL)) g -= 3;
        if (f.getUUID().equals(Villages.elder(village))) g -= 40;
        g += Math.floorMod(Objects.hash(f.getUUID(), "dissent"), 7) - 3;
        return new Grievance((int) Math.max(0, Math.min(100, Math.round(g))), why);
    }

    /** What this folk would make of the town's axes (its own lean, weighed with the town's), as Government.judge has it. */
    static int[] blend(UUID village, VillageFolkEntity f) {
        Identity.Rec r = Identity.known(village);
        double[] mine = Ethos.folkLean(f);
        Ethos.natureLean(f.life().traits(), mine, 1.5);
        int[] out = new int[mine.length];
        for (int i = 0; i < out.length; i++) out[i] = (int) Math.round((r == null ? 0 : r.axes[i]) * 0.5 + mine[i] * 1.5);
        return out;
    }

    /** The most aggrieved, most first, those at {@code least} or over. */
    static List<VillageFolkEntity> aggrieved(UUID village, int least, int most) {
        Government.Form form = Government.of(village);
        List<Object[]> scored = new ArrayList<>();
        for (VillageFolkEntity f : Identity.grown(village)) {
            int g = grievance(village, f, form).score();
            if (g >= least) scored.add(new Object[]{ f, g });
        }
        scored.sort(Comparator.comparingInt((Object[] o) -> -(Integer) o[1]));
        List<VillageFolkEntity> out = new ArrayList<>();
        for (Object[] o : scored) if (out.size() < most) out.add((VillageFolkEntity) o[0]);
        return out;
    }

    /** The form the aggrieved would have instead (what the petition, the chants and a rising ask for). */
    public static Government.Form wanted(UUID village) {
        Government.Form now = Government.of(village);
        Map<Government.Form, Double> sum = new EnumMap<>(Government.Form.class);
        for (VillageFolkEntity f : aggrieved(village, 30, 40)) {
            int[] b = blend(village, f);
            for (Government.Form x : Government.Form.values()) sum.merge(x, FormChoice.fit(x, b) + (FormChoice.democratic(x) ? 8 : 0), Double::sum);
        }
        Government.Form best = Government.Form.DEMOCRACY;
        double top = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Government.Form, Double> e : sum.entrySet()) {
            if (e.getKey() == now || e.getKey() == Government.Form.JUNTA || e.getKey() == Government.Form.LORD) continue;
            if (e.getValue() > top) { top = e.getValue(); best = e.getKey(); }
        }
        if (best == now) best = now == Government.Form.DEMOCRACY ? Government.Form.REEVE : Government.Form.DEMOCRACY;
        return best;
    }

    // ------------------------------------------------------------------ the morning's look

    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Identity.known(id) == null) return;
        Government.Form form = Government.of(id);
        List<VillageFolkEntity> folk = Identity.grown(id);
        if (folk.isEmpty()) return;
        double sum = 0;
        Map<String, Integer> causes = new LinkedHashMap<>();
        for (VillageFolkEntity f : folk) {
            Grievance g = grievance(id, f, form);
            sum += g.score();
            if (g.score() >= 30 && !g.why().isEmpty()) causes.merge(g.why(), 1, Integer::sum);
        }
        double target = sum / folk.size() * amplifier(form);
        if (Leader.plan(id) == Leader.Plan.FAMINE) { target += 10; causes.merge("hunger", folk.size(), Integer::sum); }
        int before = level(id);
        int rise = Government.appointed(form) ? 10 : 8;
        int now = before + (int) Math.max(-6, Math.min(rise, Math.round(target - before)));
        now = Math.max(0, Math.min(cap(form), now));
        set(id, "dissent.level", now);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(causes.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        List<String> why = new ArrayList<>();
        for (Map.Entry<String, Integer> e : top) if (why.size() < 3) why.add(e.getKey() + " (" + e.getValue() + ")");
        Ledger.note(id, "dissent.why", String.join("; ", why));
        set(id, "dissent.hot", now >= UPRISING ? ni(id, "dissent.hot") + 1 : 0);
        history(id, day, now);
        if (now >= GRUMBLE && before < GRUMBLE) log(id, day, "folk began to grumble at how the town is run: " + (why.isEmpty() ? "" : top.get(0).getKey()));
        // A petition.
        String pet = Ledger.note(id, "dissent.petition");
        if (now >= PETITION && (pet == null || pet.isEmpty()) && day - nl(id, "dissent.petitionDay", -99) >= 4) startPetition(level, v, day, top.isEmpty() ? "" : top.get(0).getKey());
        else if (pet != null && !pet.isEmpty()) petitionDay(level, v, day);
        // A protest, every third day at most.
        if (now >= PROTEST && day - nl(id, "dissent.protestDay", -99) >= 3 && aggrieved(id, 35, 99).size() >= 3) set(id, "dissent.protestDue", day + 1);
        // A democracy answers a protest with an early election: the town says at the polls what it said in the square.
        if (FormChoice.democratic(form) && now >= PROTEST) earlyElection(level, v, day);
        // A strike, where the government cannot be voted out and has refused the town this week.
        if (now >= STRIKE && Government.appointed(form) && day - nl(id, "dissent.refusedDay", -99) <= 7) set(id, "dissent.strikeDay", day + 1);
        // The rising, two mornings at boiling point.
        if (Government.appointed(form) && ni(id, "dissent.hot") >= 2) set(id, "dissent.risingDue", day + 1);
        // The watch moves first: a strong watch, a martial town, a government weak and refused.
        if (coupLikely(id, form, now)) set(id, "dissent.coupDue", day + 1);
    }

    static boolean coupLikely(UUID village, Government.Form form, int level) {
        if (form == Government.Form.JUNTA || FormChoice.democratic(form) || form == Government.Form.COMMUNE) return false;
        if (level < STRIKE || Ethos.lean(village, Ethos.Axis.WAR) < 25) return false;
        int guards = 0;
        for (VillageFolkEntity f : Identity.grown(village)) if (f.stationTask() == AssistantEntity.StationTask.GUARD) guards++;
        return guards >= 3 && Math.floorMod(Objects.hash(village, level / 5), 3) == 0;
    }

    /** A few times a minute (Politics): the afternoon's protest or rising, if one is due; grumbling in the street. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (BANNERS.containsValue(id)) {
            Assemblies.Assembly a = Assemblies.underWay(id);
            if (a == null || a.kind != Assemblies.Kind.DISSENT && a.kind != Assemblies.Kind.REVOLUTION) bannersBack(level, v);
        }
        if (t < FROM || t >= TO || Raids.underAlarm(id) || Assemblies.underWay(id) != null) {
            grumble(level, v, t);
            return;
        }
        if (nl(id, "dissent.coupDue", -1) == day + 1 || nl(id, "dissent.coupDue", -1) == day) {
            set(id, "dissent.coupDue", -1);
            begin(level, v, day, "COUP");
        } else if (nl(id, "dissent.risingDue", -1) == day + 1 || nl(id, "dissent.risingDue", -1) == day) {
            set(id, "dissent.risingDue", -1);
            begin(level, v, day, "RISING");
        } else if (nl(id, "dissent.protestDue", -1) == day + 1 || nl(id, "dissent.protestDue", -1) == day) {
            set(id, "dissent.protestDue", -1);
            begin(level, v, day, "PROTEST");
        }
    }

    static void grumble(ServerLevel level, Villages.Village v, long t) {
        UUID id = v.id();
        RandomSource r = level.getRandom();
        if (level(id) < GRUMBLE || t < 1500 || t > 12000 || r.nextInt(20) != 0) return;
        List<VillageFolkEntity> angry = aggrieved(id, 35, 6);
        if (angry.isEmpty()) return;
        VillageFolkEntity f = angry.get(r.nextInt(angry.size()));
        if (f.isSleeping() || Assemblies.attending(f)) return;
        Government.Form form = Government.of(id);
        FolkTalk.speak(f, switch (form) {
            case PLANNED -> FolkTalk.pick(r, "Another quota. Another queue.", "I could run a better shop than the state's.", "Equal pay for unequal work.",
                "Ask the committee. Always ask the committee.", "Rations again.", "Nobody asked me.");
            case JUNTA -> FolkTalk.pick(r, "Indoors by dark, like children.", "More guards than gardeners.", "Who voted for the general? Nobody.",
                "Keep your head down, they say.", "Another check at the gate.", "I miss saying what I think.");
            case LORD -> FolkTalk.pick(r, "The lord's dues again.", "Born to it, and never asked.", "Wouldn't mind a say, now and then.",
                "Good harvest. For the lord.", "The house does well. The rest of us less so.", "Hunting's the lord's. Of course it is.");
            case DEMOCRACY, REEVE -> FolkTalk.pick(r, "I'll remember this at the next election.", "They promised otherwise.", "Write to the council, they said.",
                "Not what I voted for.", "Ten days till the next vote.", "Somebody ought to get a petition up.");
            default -> FolkTalk.pick(r, "Things could be run better.", "Nobody listens.", "Same as ever.", "Somebody ought to say something.",
                "We'll see how long this lasts.", "It's not right.");
        });
    }

    // ------------------------------------------------------------------ the petition

    static void startPetition(ServerLevel level, Villages.Village v, long day, String cause) {
        UUID id = v.id();
        List<VillageFolkEntity> angry = aggrieved(id, 40, 99);
        if (angry.isEmpty()) return;
        int needed = Math.max(3, (Identity.grown(id).size() + 2) / 3);
        String asks = petitionAsks(id);
        Ledger.note(id, "dissent.petition", day + "|" + asks.replace("|", " ") + "|0|" + needed + "|" + angry.get(0).displayNameCap().replace("|", " "));
        Ledger.note(id, "dissent.signers", "");
        set(id, "dissent.petitionDay", day);
        String line = angry.get(0).displayNameCap() + " got up a petition to the " + Government.title(id) + ": " + asks + " (" + needed + " names wanted)";
        log(id, day, line);
        Villages.tell(id, day, line);
        FolkTalk.speak(angry.get(0), "There's a petition at the board. Put your name to it if you agree.");
    }

    /** What the petition asks: a reform of the government where it cannot be voted out; else the most hated tax or law undone. */
    static String petitionAsks(UUID village) {
        Government.Form form = Government.of(village);
        if (Government.appointed(form) || form == Government.Form.COUNCIL || form == Government.Form.GUILD) {
            return "a vote on being ruled by " + wanted(village).words;
        }
        if (TaxUnrest.level(village) >= 30) return "lower taxes";
        if (LawBook.curfew(village)) return "an end to the curfew";
        return "the council to listen";
    }

    /** Each morning a petition is open: names come in (the walkers' and the rest); full, it is put to the leader. */
    static void petitionDay(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String[] p = Ledger.note(id, "dissent.petition").split("\\|", -1);
        if (p.length < 5) { Ledger.note(id, "dissent.petition", ""); return; }
        int signed = signers(id).size() + aggrieved(id, 50, 99).size() / 2;
        int needed = Fame.number(p[3]);
        long since = Fame.number(p[0]);
        Ledger.note(id, "dissent.petition", p[0] + "|" + p[1] + "|" + signed + "|" + p[3] + "|" + p[4]);
        if (signed >= needed) present(level, v, day, p[1], signed);
        else if (day - since > 6) {
            Ledger.note(id, "dissent.petition", "");
            log(id, day, "the petition for " + p[1] + " lapsed with " + signed + " of " + needed + " names");
        }
    }

    static Set<String> signers(UUID village) {
        String s = Ledger.note(village, "dissent.signers");
        Set<String> out = new HashSet<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(",")) out.add(x);
        return out;
    }

    /**
     * The petition put to the leader. Where the town can vote its government out, the council takes it up and gives
     * ground (a tax cut, the hated law undone). Elsewhere the leader decides: a reform question put to the town, or nothing.
     */
    static void present(ServerLevel level, Villages.Village v, long day, String asks, int names) {
        UUID id = v.id();
        Ledger.note(id, "dissent.petition", "");
        Government.Form form = Government.of(id);
        VillageFolkEntity leader = Identity.leader(id);
        boolean gives;
        if (FormChoice.democratic(form) || form == Government.Form.COMMUNE) gives = true;
        else {
            int chance = 35 + (leader != null && leader.life().has(Social.Trait.GENEROUS) ? 20 : 0) + (leader != null && leader.life().has(Social.Trait.SOCIABLE) ? 10 : 0)
                - (form == Government.Form.JUNTA ? 30 : 0) - (form == Government.Form.PLANNED ? 10 : 0);
            gives = Math.floorMod(Objects.hash(id, day, "petition"), 100) < chance;
        }
        String who = Government.title(id) + (leader == null ? "" : " " + leader.displayNameCap());
        if (gives) {
            String did = concede(level, v, day, asks);
            add(id, -15);
            String line = "the petition for " + asks + " (" + names + " names) was put to the " + who + ", who gave way: " + did;
            log(id, day, line);
            Villages.tell(id, day, line);
        } else {
            add(id, 8);
            set(id, "dissent.refusedDay", day);
            String line = "the petition for " + asks + " (" + names + " names) was put to the " + who + ", who would not hear of it";
            log(id, day, line);
            Villages.tell(id, day, line);
        }
    }

    /** Ground given: a reform question put to the town, the curfew lifted, a tax cut. Returns what was done, in words. */
    static String concede(ServerLevel level, Villages.Village v, long day, String asks) {
        UUID id = v.id();
        Identity.Rec r = Identity.known(id);
        if (asks.startsWith("a vote on being ruled by") && r != null && !Government.asked(id)) {
            Government.callReform(level, v, r, wanted(id), day);
            return "the town votes tomorrow on " + wanted(id).words;
        }
        if (asks.contains("curfew") && r != null) {
            LawBook.enact(level, v, r, LawBook.Law.CURFEW, 0, day, "the curfew lifted, answering the petition");
            return "the curfew is lifted";
        }
        TaxBook.Motion m = TaxBook.motion(level, v, true);
        if (m != null) {
            TaxBook.set(level, v, m.lever(), m.to(), "answering the petition");
            return m.lever().words + " comes down a step";
        }
        return "the council will hear the petitioners at its next meeting";
    }

    // ------------------------------------------------------------------ the protest and the rising

    /** A protest ("PROTEST"), the town's rising ("RISING") or the watch seizing the hall ("COUP"), gathered now before the board. */
    static boolean begin(ServerLevel level, Villages.Village v, long day, String what) {
        UUID id = v.id();
        List<VillageFolkEntity> crowd = what.equals("COUP") ? Regimes.officers(id, Identity.grown(id)) : aggrieved(id, 30, 30);
        crowd.removeIf(f -> f.isSleeping() || f.isHired() || f.trip() != null);
        if (crowd.size() < 3) return false;
        BlockPos at = Polls.speechSpot(level, v);
        Direction face = VillageBoards.facingOf(id);
        Assemblies.Assembly a = new Assemblies.Assembly(id, what.equals("PROTEST") ? Assemblies.Kind.DISSENT : Assemblies.Kind.REVOLUTION, what, day,
            at, face != null ? face : Direction.SOUTH, Assemblies.Layout.ARC);
        Set<UUID> invited = new HashSet<>();
        for (VillageFolkEntity f : crowd) invited.add(f.getUUID());
        if (!what.equals("COUP")) for (VillageFolkEntity f : Identity.grown(id)) if (invited.size() < 30 && f.blockPosition().closerThan(at, 48)) invited.add(f.getUUID());
        a.invited = invited;
        if (what.equals("PROTEST")) set(id, "dissent.protestDay", day);
        return Assemblies.beginNow(level, a);
    }

    /** The ringleader, the leader called out, the banners in hand (Assemblies.prepare). */
    static void prepare(ServerLevel level, Assemblies.Assembly a) {
        UUID id = a.village;
        a.principals.clear();
        if (a.subject.equals("COUP")) {
            VillageFolkEntity g = Regimes.generalChoice(id, Villages.elder(id));
            if (g != null) a.host = g.getUUID();
            for (VillageFolkEntity f : Regimes.officers(id, Identity.grown(id))) if (!f.getUUID().equals(a.host) && a.principals.size() < 4) a.principals.add(f.getUUID());
            return;
        }
        List<VillageFolkEntity> angry = aggrieved(id, 30, 30);
        angry.sort(Comparator.comparingInt((VillageFolkEntity f) -> -(f.life().has(Social.Trait.SOCIABLE) ? 15 : 0) - (f.life().has(Social.Trait.GRUMPY) ? 8 : 0)));
        if (!angry.isEmpty()) a.host = angry.get(0).getUUID();
        VillageFolkEntity leader = Identity.leader(id);
        if (leader != null && !leader.getUUID().equals(a.host)) a.principals.add(leader.getUUID());
        DyeColor c = Regimes.colour(wanted(id));
        for (VillageFolkEntity f : angry) {
            if (BANNERS.containsKey(f.getUUID())) continue;
            boolean off = f.getOffhandItem().isEmpty(), main = f.getMainHandItem().isEmpty();
            if (!off && !main) continue;
            f.setItemInHand(off ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, new ItemStack(BannerBlock.byColor(c).asItem()));
            BANNERS.put(f.getUUID(), id);
        }
    }

    static int bannersBack(ServerLevel level, Villages.Village v) {
        int back = 0;
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(BANNERS.entrySet())) {
            if (!e.getValue().equals(v.id())) continue;
            BANNERS.remove(e.getKey());
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f)) continue;
            for (InteractionHand h : InteractionHand.values()) {
                if (f.getItemInHand(h).getItem() instanceof BannerItem) { f.setItemInHand(h, ItemStack.EMPTY); back++; break; }
            }
        }
        return back;
    }

    /** Is this folk carrying one of a protest's banners? */
    public static boolean holdsBanner(VillageFolkEntity f) {
        return BANNERS.containsKey(f.getUUID());
    }

    /** What is said: the chants, the ringleader's case, the answer; or the rising, or the watch's seizure. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        UUID id = a.village;
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        Government.Form form = Government.of(id);
        Government.Form want = wanted(id);
        String town = Villages.name(id), title = Government.title(id);
        List<UUID> crowd = new ArrayList<>(a.invited == null ? Set.of() : a.invited);
        crowd.remove(a.host);
        crowd.sort(Comparator.comparing(UUID::toString));
        if (a.subject.equals("COUP")) {
            s.add(new Assemblies.Line(null, "The watch has the hall. Stay calm, and go home.", '~', null));
            s.add(new Assemblies.Line(null, FolkTalk.pick(r, "The town can't go on like this. The watch will see to it.", "Order first. Everything else after.",
                "Nobody will be hurt who stays at home.", "There'll be a curfew from tonight.", "The officers will keep the peace.",
                "Questions later."), '?', null));
            UUID old = Villages.elder(id);
            s.add(new Assemblies.Line(null, "From today " + town + " is under military rule.", '?', () -> carry(level, v, Government.Form.JUNTA, old, day, true)));
            return;
        }
        String[] chants = chants(form, want, r);
        for (int i = 0; i < Math.min(3, crowd.size()); i++) s.add(new Assemblies.Line(crowd.get(i), chants[i % chants.length], '!', null));
        String why = Ledger.note(id, "dissent.why");
        String cause = why == null || why.isEmpty() ? "nobody listens to us" : why.split(";")[0].replaceAll(" \\(\\d+\\)$", "").trim();
        if (a.subject.equals("RISING")) {
            s.add(new Assemblies.Line(null, "We've asked, and we've been refused. Enough.", '!', null));
            s.add(new Assemblies.Line(null, "From today " + town + " is " + want.words + ". The old order's done.", '!', null));
            UUID old = Villages.elder(id);
            s.add(new Assemblies.Line(old, FolkTalk.pick(r, "Then I'll go.", "You'll regret this.", "So be it.", "I did what I thought right.",
                "I'll not stand in the way.", "Have it your way."), ' ', () -> carry(level, v, want, old, day, false)));
            return;
        }
        s.add(new Assemblies.Line(null, "Hear us: " + cause + ".", '?', null));
        UUID answers = a.principals.isEmpty() ? null : a.principals.get(0);
        String lead = answers == null ? "The " + title + " sends word: " : "";
        switch (form) {
            case JUNTA -> {
                s.add(new Assemblies.Line(answers, lead + "This gathering has no leave. Disperse.", '?', () -> refused(level, v, day, 6, "the watch broke up the protest")));
                s.add(new Assemblies.Line(null, FolkTalk.pick(r, "We'll be back.", "You can't arrest all of us.", "This isn't over."), '?', null));
            }
            case DEMOCRACY, REEVE, COMMUNE -> {
                s.add(new Assemblies.Line(answers, lead + "You're heard. The council takes it up this week, and the next election is yours to decide.", '!',
                    () -> {
                        String did = concede(level, v, day, petitionAsks(id));
                        add(id, -10);
                        log(id, day, "the protest in the square was answered: " + did);
                    }));
                s.add(new Assemblies.Line(null, FolkTalk.pick(r, "We'll hold you to it.", "Good. See that you do.", "We'll be watching the vote."), '!', null));
            }
            default -> {
                boolean gives = Math.floorMod(Objects.hash(id, day, "protest"), 100) < (form == Government.Form.PLANNED ? 25 : 40);
                if (gives) {
                    s.add(new Assemblies.Line(answers, lead + "Very well. Put it to the town.", '!', () -> {
                        String did = concede(level, v, day, petitionAsks(id));
                        add(id, -12);
                        log(id, day, "the protest in the square won ground: " + did);
                    }));
                } else {
                    s.add(new Assemblies.Line(answers, lead + "Things stay as they are. Go home.", '?', () -> refused(level, v, day, 6, "the protest was refused")));
                    s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Then we'll stop work.", "You'll hear from us again.", "Not for long."), '?', null));
                }
            }
        }
    }

    static String[] chants(Government.Form form, Government.Form want, RandomSource r) {
        return switch (form) {
            case PLANNED -> new String[]{ "Let us work for ourselves!", "Our hands, our say!", "Quotas don't feed a family!", "Open the shops!",
                "Ask the town, not the committee!", "A vote, not a ration book!" };
            case JUNTA -> new String[]{ "Lift the curfew!", "Back to barracks!", "A vote for the town!", "Streets for folk, not soldiers!",
                "We'll choose who leads!", "Enough orders!" };
            case LORD -> new String[]{ "A say for the town!", "No more dues!", "Born to rule? Prove it!", "The town before the house!",
                "Let us choose!", "Fair shares!" };
            case CHAPLAIN -> new String[]{ "A town for everyone!", "Let us choose our own leader!", "Faith is not a law!", "Open the tavern!",
                "A vote, not a sermon!", "Our town, our choice!" };
            case DEMOCRACY, REEVE -> new String[]{ "Listen to the town!", "Keep your promises!", "We voted for better!", "Lower the taxes!",
                "Council, hear us!", "Not in our name!" };
            default -> new String[]{ "Hear us!", "Our town, our say!", "Things must change!", "Enough!", "Listen!", "Fair play!" };
        };
    }

    static void refused(ServerLevel level, Villages.Village v, long day, int by, String what) {
        add(v.id(), by);
        set(v.id(), "dissent.refusedDay", day);
        log(v.id(), day, what);
    }

    /**
     * The change made: the old leader leaves (to a neighbour, by the road, if there is one), the old order's posters and flag
     * come down, the new flag goes up, and the town takes up its new government. {@code coup}: the watch's seizure.
     */
    static void carry(ServerLevel level, Villages.Village v, Government.Form to, @Nullable UUID oldLeader, long day, boolean coup) {
        UUID id = v.id();
        Identity.Rec r = Identity.known(id);
        if (r == null) return;
        Government.Form was = r.gov;
        String oldName = Villages.elderName(id);
        if (PlayerLeader.leaderId(id) != null) PlayerLeader.leave(level, v, day, coup ? "put out by the watch" : "put out by the town's rising");
        if (oldLeader != null) {
            Ledger.note(id, "elder", "");
            Ledger.note(id, "mandate", "");
            Villages.elderGone(id, oldLeader);
        }
        Government.set(level, v, r, to, day, coup ? "the watch seized the hall" : "the town rose against " + (oldName.isEmpty() ? "its rulers" : oldName));
        // The new order's first pick fell on the very one put out (a guard lord, say, for the general): the next instead.
        if (oldLeader != null && oldLeader.equals(Villages.elder(id))) Government.vacancy(level, id, oldName, day);
        String line = coup ? "the watch seized the hall: " + Villages.name(id) + " is under military rule, and " + (oldName.isEmpty() ? "the old order" : oldName) + " is out"
            : Villages.name(id) + " rose against " + was.words + (oldName.isEmpty() ? "" : " and " + oldName) + ": the town is " + to.words + " from today";
        log(id, day, line);
        Villages.tell(id, day, line);
        set(id, "dissent.level", Math.max(0, level(id) - 40));
        set(id, "dissent.hot", 0);
        Ledger.note(id, "dissent.lastChange", day + "|" + (coup ? "coup" : "revolution") + "|" + was.name() + "|" + to.name() + "|" + oldName.replace("|", " "));
        if (oldLeader != null && level.getEntity(oldLeader) instanceof VillageFolkEntity f) exile(level, v, f, day);
    }

    /** The old leader leaves the town: on the road to a neighbour if there is one (Newcomers), else it steps down and stays. */
    static void exile(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        f.persona().remember(day, "I was put out of office", 9);
        Villages.Village to = null;
        for (Villages.Village o : Diplomacy.neighboursOf(v.id())) {
            if (!Wars.atWar(v.id(), o.id())) { to = o; break; }
        }
        if (to == null || Villages.headcount(v.id()) <= 4) {
            Villages.tell(v.id(), day, f.displayNameCap() + " stepped down, and keeps to the house");
            return;
        }
        Newcomers.send(level, v, to, List.of(f), Newcomers.Cause.POLITICS);
        Villages.tell(v.id(), day, f.displayNameCap() + " left " + Villages.name(v.id()) + " for " + Villages.name(to.id()));
    }

    static void closed(ServerLevel level, Assemblies.Assembly a) {
        Villages.Village v = Villages.get(a.village);
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        bannersBack(level, v);
        int n = a.seated.size() + (a.host == null ? 0 : 1);
        if (a.kind == Assemblies.Kind.DISSENT) Villages.tell(a.village, day, n + " folk protested in the square against how the town is run");
        for (UUID u : a.seated.keySet()) {
            if (level.getEntity(u) instanceof VillageFolkEntity f) f.persona().remember(day, a.kind == Assemblies.Kind.REVOLUTION ? "I was there the day the town changed"
                : "I protested in the square", a.kind == Assemblies.Kind.REVOLUTION ? 8 : 4);
        }
    }

    // ------------------------------------------------------------------ the strike and the petition's signers

    /** Is this folk on strike now (VillageFolkEntity.onShift): one of the aggrieved, the strike's day, by day. */
    public static boolean striking(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.GUARD || Identity.neutral()) return false;
        long day = f.level().getDayTime() / 24000L, t = f.level().getDayTime() % 24000L;
        if (nl(id, "dissent.strikeDay", -1) != day + 1 || t < 1000 || t > 11500 || level(id) < STRIKE) return false;
        return grievance(id, f, Government.of(id)).score() >= 45;
    }

    /** A striker's day before the hall, or a walk to the board to sign the petition (VillageFolkEntity.bedtime). True while at it. */
    public static boolean picket(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || Identity.neutral()) return false;
        UUID id = f.ownerId();
        if (id == null) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        if (striking(f)) {
            BlockPos hall = TaxBook.hall(level, v);
            int slot = Math.floorMod(f.getUUID().hashCode(), 12);
            double ang = Math.PI * 2 * slot / 12.0;
            BlockPos at = hall.offset((int) Math.round(Math.cos(ang) * 5), 0, (int) Math.round(Math.sin(ang) * 5));
            f.hobbyNow = "on strike";
            f.lastLeisureTick = f.tickCount;
            if (!Civics.goTo(f, at, 1.5, 0.8)) return true;
            f.getLookControl().setLookAt(hall.getX() + 0.5, hall.getY() + 1.5, hall.getZ() + 0.5);
            if (f.tickCount % 200 == 0 && f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "On strike. Ask the " + Government.title(id) + " why.", "Not a stroke of work today.",
                    "Down tools till they listen.", "We'll work when we're heard.", "The hall can dig its own fields.", "Strike!"));
                FolkAction.play(f, FolkAction.Act.PROTEST, 60);
            }
            return true;
        }
        return sign(level, v, f);
    }

    /** Off to the board to put its name to the petition: once, of an afternoon, when its work is done. */
    static boolean sign(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        String pet = Ledger.note(id, "dissent.petition");
        long t = level.getDayTime() % 24000L;
        UUID me = f.getUUID();
        if (pet == null || pet.isEmpty() || t < 9000 || t > 12500 || f.isSleeping() || f.getTarget() != null) { SIGNING.remove(me); return false; }
        Set<String> signed = signers(id);
        if (signed.contains(me.toString())) return false;
        Long since = SIGNING.get(me);
        if (since == null) {
            if (Math.floorMod(me.hashCode() + level.getGameTime() / 400L, 6) != 0 || grievance(id, f, Government.of(id)).score() < 40) return false;
            since = level.getGameTime();
            SIGNING.put(me, since);
        }
        BlockPos board = Polls.speechSpot(level, v);
        f.hobbyNow = "signing the petition";
        f.lastLeisureTick = f.tickCount;
        if (level.getGameTime() - since > 1600) { SIGNING.remove(me); return false; }
        if (!Civics.goTo(f, board, 2.2, 0.85)) return true;
        f.getLookControl().setLookAt(board.getX() + 0.5, board.getY() + 1.2, board.getZ() + 0.5);
        FolkAction.play(f, FolkAction.Act.WRITE, 50);
        signed.add(me.toString());
        Ledger.note(id, "dissent.signers", String.join(",", signed));
        SIGNING.remove(me);
        if (f.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. My name's on it.", "Signed.", "About time somebody got this up.",
            "One more name.", "Put me down for that.", "I'll sign that."));
        return true;
    }

    // ------------------------------------------------------------------ what lets it out, what stirs it

    /** A count held (Polls.counted): a democracy's anger let out at the polls. */
    static void letOut(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int by = FormChoice.democratic(Government.of(id)) ? 15 : 8;
        if (level(id) > 0) {
            add(id, -by);
            if (level(id) + by >= PETITION) log(id, day, "the election let off steam: the unrest fell by " + by);
        }
    }

    /** A democracy's protest: the next election brought forward, if it is more than three days off. */
    static void earlyElection(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Elections.Campaign c = Elections.campaign(id);
        if (c != null && !c.counted) return;
        long next = Elections.nextVote(id, day);
        if (next - day <= 3) return;
        Ledger.note(id, "election.next", Long.toString(day + 2));
        String line = "the council, with the town in the square, brought the election forward to day " + (day + 2);
        log(id, day, line);
        Villages.tell(id, day, line);
    }

    /** The form changed: the slate wiped mostly clean, the petition and the strike off. */
    static void formChanged(ServerLevel level, Villages.Village v, Government.Form was, Government.Form to, long day) {
        UUID id = v.id();
        set(id, "dissent.level", Math.max(0, level(id) - 30));
        Ledger.note(id, "dissent.petition", "");
        set(id, "dissent.strikeDay", -1);
        set(id, "dissent.risingDue", -1);
        set(id, "dissent.coupDue", -1);
        set(id, "dissent.hot", 0);
        log(id, day, "the town changed its government, from " + was.words + " to " + to.words);
    }

    /** Talk from abroad (Ideology: a neighbour's pamphlets, or news of its elections): so much more grievance for a few days. */
    public static void stir(UUID village, long day, int by, String from) {
        Ledger.note(village, "dissent.stirred", (day + 4) + "|" + by + "|" + from.replace("|", " "));
    }

    static int stirred(UUID village, long day) {
        String s = Ledger.note(village, "dissent.stirred");
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split("\\|");
        return p.length >= 2 && day <= Fame.number(p[0]) ? Fame.number(p[1]) : 0;
    }

    static String stirredBy(UUID village) {
        String s = Ledger.note(village, "dissent.stirred");
        String[] p = s == null ? new String[0] : s.split("\\|");
        return p.length >= 3 ? p[2] : "abroad";
    }

    // ------------------------------------------------------------------ the books

    static void history(UUID village, long day, int level) {
        String s = Ledger.note(village, "dissent.history");
        List<String> h = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(",")) h.add(x);
        if (!h.isEmpty() && h.get(h.size() - 1).startsWith(day + ":")) h.remove(h.size() - 1);
        h.add(day + ":" + level);
        while (h.size() > 20) h.remove(0);
        Ledger.note(village, "dissent.history", String.join(",", h));
    }

    /** The meter's last days, {day, level}. */
    public static List<int[]> historyOf(UUID village) {
        String s = Ledger.note(village, "dissent.history");
        List<int[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String x : s.split(",")) {
            String[] p = x.split(":");
            if (p.length == 2) out.add(new int[]{ Fame.number(p[0]), Fame.number(p[1]) });
        }
        return out;
    }

    static void log(UUID village, long day, String line) {
        String s = Ledger.note(village, "dissent.log");
        List<String> h = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(";;")) h.add(x);
        h.add("Day " + (day + 1) + ": " + Regimes.capital(line).replace(";;", ";"));
        while (h.size() > 10) h.remove(0);
        Ledger.note(village, "dissent.log", String.join(";;", h));
    }

    public static List<String> logOf(UUID village) {
        String s = Ledger.note(village, "dissent.log");
        List<String> h = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(";;")) h.add(x);
        return h;
    }

    /** The petition open now: {asks, signed, needed, by}, or null. */
    @Nullable
    public static String[] petition(UUID village) {
        String s = Ledger.note(village, "dissent.petition");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", -1);
        return p.length < 5 ? null : new String[]{ p[1], p[2], p[3], p[4] };
    }

    public static String why(UUID village) {
        String s = Ledger.note(village, "dissent.why");
        return s == null ? "" : s;
    }

    static int ni(UUID village, String key) {
        String s = Ledger.note(village, key);
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static long nl(UUID village, String key, long dflt) {
        String s = Ledger.note(village, key);
        if (s == null || s.isEmpty()) return dflt;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    static void set(UUID village, String key, long value) {
        Ledger.note(village, key, Long.toString(value));
    }

    static void add(UUID village, int by) {
        set(village, "dissent.level", Math.max(0, Math.min(100, level(village) + by)));
    }

    // ------------------------------------------------------------------ tests

    public static void setForTests(UUID village, int level) {
        set(village, "dissent.level", level);
    }

    public static void dailyForTests(ServerLevel level, Villages.Village v) {
        daily(level, v, level.getDayTime() / 24000L);
    }

    public static boolean beginForTests(ServerLevel level, Villages.Village v, String what) {
        return begin(level, v, level.getDayTime() / 24000L, what);
    }

    public static void carryForTests(ServerLevel level, Villages.Village v, Government.Form to, boolean coup) {
        carry(level, v, to, Villages.elder(v.id()), level.getDayTime() / 24000L, coup);
    }

    public static void startPetitionForTests(ServerLevel level, Villages.Village v) {
        startPetition(level, v, level.getDayTime() / 24000L, "");
    }

    public static void presentForTests(ServerLevel level, Villages.Village v) {
        String[] p = petition(v.id());
        present(level, v, level.getDayTime() / 24000L, p == null ? petitionAsks(v.id()) : p[0], p == null ? 9 : Fame.number(p[1]));
    }

    public static int grievanceForTests(UUID village, VillageFolkEntity f) {
        return grievance(village, f, Government.of(village)).score();
    }
}
