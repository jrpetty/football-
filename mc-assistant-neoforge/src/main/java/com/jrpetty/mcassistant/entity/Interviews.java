package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.InterviewBook.Cand;
import com.jrpetty.mcassistant.entity.InterviewBook.Interview;
import com.jrpetty.mcassistant.entity.InterviewBook.Ref;
import com.jrpetty.mcassistant.entity.InterviewBook.Stage;
import com.jrpetty.mcassistant.entity.InterviewPosts.Post;
import com.jrpetty.mcassistant.entity.InterviewScript.Act;
import com.jrpetty.mcassistant.entity.InterviewScript.Line;
import com.jrpetty.mcassistant.item.InterviewItems;
import com.jrpetty.mcassistant.item.LetterOfApplicationItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [interviews] Job interviews a player can walk up to and watch.
 *
 * <p><b>When.</b> A notice on the board with two or more fit to take it (JobMarket.judge hands it here: a shortlist of
 * the best three, a fourth if it is close, the rest told no on paper and kindly), a new workplace's first keeper (the
 * town's own who want it stand with the applicants), and the posts the town gives its own (InterviewPosts): the post,
 * fallen vacant, is held open a day for its interview, when two or more of the town's own want it — by their ambition,
 * what they care for, their nature, their hand at its trade, and being free to take it without leaving a trade short.
 * One alone is given it unopposed. Never more than one at a time in a town; never at night, in a raid, a fire, a
 * thunderstorm or on a festival (it is put off a day, three times at most, and then the panel decides on paper).
 *
 * <p><b>The day before.</b> Set for the next morning at the hall, and told on the board, by the crier, in the gazette and
 * at the morning assembly. Each candidate writes its letter of application (a paper and an ink sac out of its own
 * town's stores, paid for out of its purse). One from another town sets off along the road in time to get here (the
 * ground it crosses kept awake, as for any traveller), is paid its room at the inn by the town, and waits about the board
 * for the morning; one that cannot come has its letter read out instead. The table is found (the meeting hall's) or set
 * out by the board (InterviewTable).
 *
 * <p><b>The interview.</b> The panel — the leader in the chair (or, for a player who leads, its steward, the player
 * choosing), the post's master or the trade's best hand, a councillor for a big post, and an honoured guest if one was
 * asked to sit — take their chairs, the candidates the bench, letters in hand. Each in turn is called across the table
 * and the talk goes back and forth a line every few seconds (InterviewScript), the others on the bench fidgeting and
 * whispering. Then the candidates file out, the panel huddles and confers, and the chair calls them back: the choice,
 * and why in a sentence; a handshake across the table; each of the others thanked and told what to work at.
 *
 * <p><b>After.</b> The chosen takes up the post (the notice filled and the newcomer off home to fetch its things and come
 * back for good, or the post's own appointing taking the panel's choice); the chronicle, the gazette and every
 * candidate's card tell it; the others' spirits dip a little, they work the harder for a week, a friend consoles them,
 * and some go to read the board for a place elsewhere.
 *
 * <p>The interview's own clock runs once a second, and only for a town with an interview on; folk are held by it a
 * look every fourth tick. The table is found once and kept (InterviewTable).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Interviews {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Interviews() {}

    /** How far the interview can move a candidate from where it stood on paper, either way. */
    static final int SWAY = 12;
    /** Between lines (ticks): long enough to read a bubble. */
    static final int LINE = 70;
    /** The hour it begins (after the morning assembly), and the last it is begun at. */
    static final long START = 2400L, LATEST = 8000L;
    /** The chosen of a post the town reckons daily is kept while fit; one wanted by the post's own choosing comes first. */
    static final int BONUS = 1_000_000;

    /** Held of the towns' own accord (off in the tests, which set them going themselves). */
    private static volatile boolean auto = true;
    /** Tests and the stage: lines every half-second, short waits. */
    private static volatile boolean hurry;
    private static final Set<UUID> PROUD_FOR_TESTS = ConcurrentHashMap.newKeySet();
    /** Tests: these want any post going, whatever their nature. */
    static final Set<UUID> KEEN_FOR_TESTS = ConcurrentHashMap.newKeySet();
    /** Tests: no festival puts an interview off (the test's day may fall on one). */
    private static volatile boolean fairDay;

    /** Who an interview holds: folk → (its town, the interview). */
    record Role(UUID village, int id) {}

    private static final Map<UUID, Role> ROLES = new ConcurrentHashMap<>();
    /** One from another town on the road to its interview, or home from it. */
    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    /** The day each town last looked over its posts. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** How each held folk's walk to its place is going: {nearest, since tick, last path tick}. */
    private static final Map<UUID, double[]> WALKS = new ConcurrentHashMap<>();
    /** The interview the operator's stage last set in each town. */
    private static final Map<UUID, Integer> STAGED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ROLES.clear();
        VISITS.clear();
        LOOKED.clear();
        WALKS.clear();
        STAGED.clear();
        // A world opening: what is kept with it stays (the book is the world's own); between tests, an empty book.
        if (!com.jrpetty.mcassistant.SessionReset.opening()) {
            auto = false;
            hurry = false;
            fairDay = false;
            PROUD_FOR_TESTS.clear();
            KEEN_FOR_TESTS.clear();
            InterviewBook.of().clear();
        }
    }

    /** Tests: the towns hold their interviews of their own accord. */
    public static void liveForTests(boolean on) {
        auto = on;
    }

    /** Tests and the stage: lines every half-second. */
    public static void hurryForTests(boolean on) {
        hurry = on;
    }

    /** Tests: no festival puts an interview off. */
    public static void fairDayForTests(boolean on) {
        fairDay = on;
    }

    /** Tests: this folk wants any post that goes to interview (it puts itself forward). */
    public static void keenForTests(VillageFolkEntity f) {
        KEEN_FOR_TESTS.add(f.getUUID());
    }

    /** Tests: this folk is the proud sort, that boasts. */
    public static void proudForTests(VillageFolkEntity f) {
        PROUD_FOR_TESTS.add(f.getUUID());
    }

    static boolean live() {
        return auto;
    }

    private static int line() {
        return hurry ? 12 : LINE;
    }

    private static long waitMost(long usual) {
        return hurry ? Math.max(40, usual / 6) : usual;
    }

    /** The proud sort: wants to be the best at its trade, and is not shy of saying so. */
    static boolean proud(VillageFolkEntity f) {
        if (PROUD_FOR_TESTS.contains(f.getUUID())) return true;
        return f.persona().rolled() && f.persona().ambition() == Persona.Ambition.MASTER && !f.life().has(Social.Trait.SHY)
            && (f.life().has(Social.Trait.SOCIABLE) || f.life().has(Social.Trait.HARDWORKING));
    }

    // ------------------------------------------------------------------ the books

    static List<Interview> of(UUID village) {
        InterviewBook.Town t = InterviewBook.of().towns.get(village);
        return t == null ? List.of() : t.list;
    }

    @Nullable
    static Interview find(UUID village, int id) {
        for (Interview iv : of(village)) if (iv.id == id) return iv;
        return null;
    }

    /** An interview not yet held (or being held) for this post. */
    @Nullable
    static Interview pending(UUID village, String post) {
        for (Interview iv : of(village)) if (iv.stage.open() && iv.post.equals(post)) return iv;
        return null;
    }

    /** The town's interviews, the coming and the running, soonest first. */
    public static List<Interview> coming(UUID village) {
        List<Interview> out = new ArrayList<>();
        for (Interview iv : of(village)) if (iv.stage.open()) out.add(iv);
        out.sort(Comparator.comparingLong((Interview iv) -> iv.dueDay).thenComparingLong(iv -> iv.dueTime));
        return out;
    }

    /** The town's interviews held, the latest first. */
    public static List<Interview> held(UUID village) {
        List<Interview> out = new ArrayList<>();
        for (Interview iv : of(village)) if (iv.stage == Stage.DONE) out.add(iv);
        out.sort(Comparator.comparingLong((Interview iv) -> -iv.decidedDay).thenComparingInt(iv -> -iv.id));
        return out;
    }

    /** The interview on in a town now, if one is. */
    @Nullable
    public static Interview running(UUID village) {
        for (Interview iv : of(village)) if (iv.stage.on() || iv.stage == Stage.AWAITING) return iv;
        return null;
    }

    // ------------------------------------------------------------------ the posts' own choosing (the hooks)

    /** Before the job market's leader decides on paper: is a notice's choosing with an interview (it waits)? */
    static boolean held(UUID village, int opening) {
        return pending(village, "opening:" + opening) != null;
    }

    /**
     * A post the town gives its own has fallen vacant and its own choosing has picked {@code best}: true if the post
     * waits for an interview (one set now, or already set); false if it is given as it always was — no interviews in
     * this world, one alone wanting it, or {@code best} is the panel's own choice.
     */
    static boolean vacancy(ServerLevel level, UUID village, String post, @Nullable VillageFolkEntity best) {
        if (!auto || best == null) return false;
        String[] chosen = heldBy(village, post);
        if (chosen != null && chosen[0].equals(best.getUUID().toString())) return false;          // the panel's choice: given
        if (pending(village, post) != null) return true;
        Villages.Village v = Villages.get(village);
        Post p = Post.of(post);
        if (v == null || p == null) return false;
        long day = level.getDayTime() / 24000L;
        InterviewBook.Town town = InterviewBook.town(village);
        Long quiet = town.quiet.get(post);
        if (quiet != null && day - quiet < 2) return false;                 // filled unopposed, or put off for good, lately
        for (Interview iv : held(village)) if (iv.post.equals(post) && day - iv.decidedDay < 2) return false;
        List<VillageFolkEntity> cands = InterviewPosts.candidates(level, v, p, best, false, Set.of());
        if (cands.size() < 2) {
            town.quiet.put(post, day);
            InterviewBook.changed();
            return false;
        }
        schedule(level, v, p, cands, List.of(), day, false);
        return true;
    }

    /** The panel's choice for a post, while it lives: preferred above all by the post's own choosing. */
    static int preferred(UUID village, String post, VillageFolkEntity f) {
        String[] chosen = heldBy(village, post);
        return chosen != null && chosen[0].equals(f.getUUID().toString()) ? BONUS : 0;
    }

    /** The panel's choice for a post the town reckons each day, loaded and alive (its holder while it is fit). */
    @Nullable
    static VillageFolkEntity holder(UUID village, String post) {
        String[] chosen = heldBy(village, post);
        if (chosen == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.getUUID().toString().equals(chosen[0])) return f;
        }
        return null;
    }

    @Nullable
    private static String[] heldBy(UUID village, String post) {
        InterviewBook.Town t = InterviewBook.of().towns.get(village);
        return t == null ? null : t.held.get(post);
    }

    /**
     * A notice with two or more fit for it (JobMarket.judge): those who would not do told so now, a shortlist (the best
     * three, a fourth if close; for a new workplace's first keeper the town's own who want it stand with them) and the
     * rest told no on paper, kindly; the interview set. True if it was (the leader does not decide on paper).
     */
    static boolean shortlist(ServerLevel level, Villages.Village v, JobMarket.Opening o, List<JobMarket.Weighed> all, long day) {
        if (!auto) return false;
        UUID id = v.id();
        Post post = Post.opening(o);
        List<JobMarket.Weighed> fit = new ArrayList<>();
        for (JobMarket.Weighed w : all) if (w.fault() == null) fit.add(w);
        List<VillageFolkEntity> own = workplace(o) ? InterviewPosts.candidates(level, v, post, null, false, Set.of()) : List.of();
        if (fit.size() + own.size() < 2) return false;
        String town = Villages.name(id);
        for (JobMarket.Weighed w : all) {
            if (w.fault() != null) JobMarket.refuse(level, id, o, w.a(), w.withdrawn(), w.fault(), null, day);
        }
        // One list, the applicants and the town's own, best on paper first.
        List<Cand> pool = new ArrayList<>();
        for (JobMarket.Weighed w : fit) pool.add(applicant(w));
        for (VillageFolkEntity f : own) pool.add(cand(post, f, id));
        pool.sort(Comparator.comparingInt((Cand c) -> -c.paper));
        int keep = Math.min(3, pool.size());
        if (pool.size() > 3 && pool.get(3).paper >= pool.get(2).paper - 5) keep = 4;
        List<Cand> shortlisted = new ArrayList<>(pool.subList(0, keep));
        for (Cand c : pool.subList(keep, pool.size())) {
            if (!c.outside) continue;
            for (JobMarket.Weighed w : fit) {
                if (!w.a().folk().equals(c.id)) continue;
                JobMarket.refuse(level, id, o, w.a(), false, "not shortlisted: " + JobMarket.words(keep) + " with more to show for it were", null, day);
                JobSeekers.told(c.id, FolkTalk.pick(level.getRandom(), town + " wrote, kindly: a shortlist of " + JobMarket.words(keep)
                    + ", and I'm not on it. Next time.", "Not shortlisted in " + town + ". They were kind about it. I'll get more years in."));
            }
        }
        List<VillageFolkEntity> internal = new ArrayList<>();
        List<Cand> outside = new ArrayList<>();
        for (Cand c : shortlisted) {
            if (c.outside) outside.add(c);
            else for (VillageFolkEntity f : own) if (f.getUUID().equals(c.id)) internal.add(f);
        }
        schedule(level, v, post, internal, outside, day, false);
        return true;
    }

    /** Is the notice for a new workplace's first keeper (the town's own may stand for it too)? */
    static boolean workplace(JobMarket.Opening o) {
        return o.why().startsWith("the new ") || o.why().equals(JobMarket.BY_HAND);
    }

    /** An applicant from another town, as a candidate: its paper is the job market's weighing. */
    private static Cand applicant(JobMarket.Weighed w) {
        JobMarket.Application a = w.a();
        Cand c = new Cand();
        c.id = a.folk;
        c.name = a.name;
        c.home = a.from;
        c.homeName = a.fromName;
        c.outside = true;
        c.trade = a.trade;
        c.level = a.level;
        c.age = a.age;
        c.knacks = a.knacks;
        c.paper = w.score();
        c.good = String.join(", ", w.good()) + (a.reasons.isEmpty() ? "" : "; applied: " + a.reasons);
        return c;
    }

    /** One of the town's own, as a candidate for the post. */
    static Cand cand(Post p, VillageFolkEntity f, UUID village) {
        Cand c = new Cand();
        c.id = f.getUUID();
        c.name = f.displayNameCap();
        c.home = f.ownerId();
        c.homeName = c.home == null ? "" : Villages.name(c.home);
        c.outside = c.home != null && !c.home.equals(village);
        StationTask t = p.tradeFor(village);
        c.trade = f.stationTask().name();
        c.level = t == null ? FolkSkills.bestLevel(f) : f.tradeLevel(t);
        c.age = f.ageYears();
        c.knacks = JobMarket.knacks(f, t);
        List<String> good = new ArrayList<>();
        c.paper = InterviewPosts.paper(p, f, village, good);
        c.good = String.join(", ", good);
        return c;
    }

    // ------------------------------------------------------------------ setting one

    /**
     * An interview set: for tomorrow morning (or the first morning after the town's last one), or now (the operator's
     * "now"). The panel chosen, the table found or set going, the candidates told and their letters written, those from
     * other towns told when to set off; the town told on the board, by the crier, in the gazette and at the assembly.
     */
    static Interview schedule(ServerLevel level, Villages.Village v, Post p, List<VillageFolkEntity> own, List<Cand> outside, long day, boolean now) {
        UUID id = v.id();
        InterviewBook.Town town = InterviewBook.town(id);
        Interview iv = new Interview();
        iv.id = ++town.seq;
        iv.village = id;
        iv.post = p.key();
        iv.title = p.title(id);
        StationTask t = p.tradeFor(id);
        iv.trade = t == null ? "" : t.name();
        iv.opening = p.opening();
        iv.big = p.big();
        iv.setDay = day;
        long due = day + 1;
        for (Interview other : coming(id)) due = Math.max(due, other.dueDay + 1);
        iv.dueDay = now ? day : due;
        iv.dueTime = now ? level.getDayTime() % 24000L : START;
        for (VillageFolkEntity f : own) iv.cands.add(cand(p, f, id));
        iv.cands.addAll(outside);
        panel(level, v, iv);
        town.list.add(iv);
        while (town.list.size() > InterviewBook.KEEP + 3) {
            Interview old = null;
            for (Interview x : town.list) if (!x.stage.open() && (old == null || x.id < old.id)) old = x;
            if (old == null) break;
            town.list.remove(old);
        }
        InterviewBook.changed();
        // The letters, the travellers, the table.
        Map<UUID, VillageFolkEntity> loaded = new LinkedHashMap<>();
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null) loaded.put(c.id, f);
        }
        for (Cand c : iv.cands) {
            VillageFolkEntity f = loaded.get(c.id);
            if (f != null) write(level, iv, c, f);
            if (c.outside) {
                Villages.Village from = Villages.get(c.home);
                int blocks = from == null ? 200 : (int) Math.sqrt(from.centre().distSqr(v.centre()));
                c.leaveAt = now ? 0 : iv.dueDay * 24000L + iv.dueTime - blocks * 7L - 2400L;
            }
        }
        InterviewTable.ready(level, v);
        String names = JobMarket.join(names(iv));
        String when = now ? "now" : iv.dueDay == day + 1 ? "tomorrow morning" : "on the morning of day " + (iv.dueDay + 1);
        String where = InterviewTable.known(id) != null && InterviewTable.known(id).hall() ? "the hall" : "the board";
        Villages.tell(id, day, "interviews were set for " + (now ? "today" : "day " + (iv.dueDay + 1)) + " at " + where + ": the post of "
            + iv.title + "; " + JobMarket.words(iv.cands.size()) + " stand (" + names + ")");
        Market.assemblyNews(id, "Interviews at " + where + " " + when + " for the post of " + iv.title + ": " + names + ".");
        for (Cand c : iv.cands) {
            card(c.id, day, "Shortlisted for " + iv.title + (c.outside ? " in " + Villages.name(id) : "") + ": interview "
                + (now ? "today" : "day " + (iv.dueDay + 1)));
            VillageFolkEntity f = loaded.get(c.id);
            if (f != null) {
                f.persona().remember(day, "I'm to be interviewed for the post of " + iv.title + " in " + Villages.name(id), 3);
                if (!c.outside) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "An interview! For " + iv.title + ". I'd better write my letter.",
                    "They've put me on the list for " + iv.title + ". Oh, my.", "Interviewed for " + iv.title + "? Me? Right. Right."));
            }
            if (c.outside) JobSeekers.told(c.id, FolkTalk.pick(level.getRandom(), Villages.name(id) + " want to see me! An interview, " + when + ".",
                "I'm on " + Villages.name(id) + "'s list for " + iv.title + ". I'll set off in good time."));
        }
        LOG.info("[MCA-INTERVIEW] {}: set for day {} at {}: {} — {} ({})", Villages.name(id), iv.dueDay + 1, iv.dueTime, iv.title, names,
            "chair " + iv.chairName + (iv.masterName.isEmpty() ? "" : ", " + iv.masterName) + (iv.councillorName.isEmpty() ? "" : ", " + iv.councillorName));
        return iv;
    }

    private static List<String> names(Interview iv) {
        List<String> out = new ArrayList<>();
        for (Cand c : iv.cands) out.add(c.name + (c.outside ? " of " + c.homeName : ""));
        return out;
    }

    /**
     * The panel: the leader in the chair (a player who leads chairs it, its steward speaking for it), the post's master or
     * the trade's best hand, and a councillor for a big post (or for want of a master). Nobody who stands sits on it.
     */
    static void panel(ServerLevel level, Villages.Village v, Interview iv) {
        UUID id = v.id();
        Set<UUID> not = new HashSet<>();
        for (Cand c : iv.cands) not.add(c.id);
        UUID player = PlayerLeader.leaderId(id);
        VillageFolkEntity chair = null;
        iv.playerChairs = player != null;
        if (player != null) {
            iv.chairName = PlayerLeader.leaderName(id);
            VillageFolkEntity s = steward(id);
            if (s != null && !not.contains(s.getUUID()) && InterviewPosts.about(s)) chair = s;
        } else {
            VillageFolkEntity elder = Orders.elderOf(id);
            if (elder != null && !not.contains(elder.getUUID()) && InterviewPosts.about(elder)) chair = elder;
        }
        if (chair == null) {
            for (VillageFolkEntity m : Council.members(id)) {
                if (!not.contains(m.getUUID()) && InterviewPosts.about(m)) { chair = m; break; }
            }
        }
        if (chair != null) {
            iv.chair = chair.getUUID();
            if (player == null) iv.chairName = Leader.leaderName(id, chair);
            not.add(chair.getUUID());
        } else {
            iv.chair = null;
            if (player == null) iv.chairName = "the town";
        }
        Post p = Post.of(iv.post);
        VillageFolkEntity master = p == null ? null : InterviewPosts.master(level, v, p, not);
        if (master != null) {
            iv.master = master.getUUID();
            iv.masterName = master.displayNameCap();
            not.add(master.getUUID());
        } else {
            iv.master = null;
            iv.masterName = "";
        }
        iv.councillor = null;
        iv.councillorName = "";
        if (iv.big || master == null) {
            for (VillageFolkEntity m : Council.members(id)) {
                if (not.contains(m.getUUID()) || !InterviewPosts.about(m)) continue;
                iv.councillor = m.getUUID();
                iv.councillorName = m.displayNameCap();
                break;
            }
        }
    }

    /** The steward a player who leads has now, as the town's notes have it (none chosen here: that has its own interview). */
    @Nullable
    private static VillageFolkEntity steward(UUID village) {
        String s = Ledger.note(village, "civic.steward");
        if (s == null || s.isEmpty()) return null;
        UUID who = parse(s.split("\\|", 2)[0]);
        return who == null ? null : Elections.loaded(village, who);
    }

    /** The Leader's page's line: the interview coming or on, its candidates, and the choice that is the leader's; or null. */
    @Nullable
    public static String leaderLine(UUID village) {
        Interview iv = running(village);
        if (iv == null && !coming(village).isEmpty()) iv = coming(village).get(0);
        if (iv == null) return null;
        String when = iv.stage == Stage.AWAITING ? "heard, and waiting on your choice till sundown"
            : iv.stage.on() ? "going on now at " + iv.where : "day " + (iv.dueDay + 1);
        return "the post of " + iv.title + ", " + when + ": " + JobMarket.join(names(iv)) + ". You chair it: the choice is yours, or the panel's.";
    }

    // ------------------------------------------------------------------ the letter of application

    /**
     * Its letter of application, in its own hand: a paper and an ink sac out of its own town's stores by their real recipe
     * (the paper made from their cane if need be: Bench), paid for out of its purse into its town's treasury. A letter it
     * carries already (from an interview before) is written over. False if it could not: no makings, or no coin.
     */
    static boolean write(ServerLevel level, Interview iv, Cand c, VillageFolkEntity f) {
        if (c.letter) return true;
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null) return false;
        long day = level.getDayTime() / 24000L;
        ItemStack letter = ItemStack.EMPTY;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (pack.get(i).is(InterviewItems.LETTER_OF_APPLICATION.get())) { letter = pack.get(i); break; }
        }
        if (f.getOffhandItem().is(InterviewItems.LETTER_OF_APPLICATION.get())) letter = f.getOffhandItem();
        boolean fresh = letter.isEmpty();
        if (fresh) {
            Bench.Plan plan;
            try {
                plan = Bench.plan(level, v, InterviewItems.LETTER_OF_APPLICATION.get(), 1, Bench.handOf(level, v, f, null));
            } catch (RuntimeException e) {
                return false;
            }
            if (!plan.ok() || plan.made <= 0) {
                LOG.info("[MCA-INTERVIEW] {} cannot write a letter: {} short of {}", c.name, Villages.name(home), plan.shortOf);
                return false;
            }
            int cost = Math.max(1, (int) Math.ceil(plan.cost()));
            if (f.purse() < cost || !Bench.take(level, v, plan, f)) return false;
            if (f.spend(cost)) Ledger.addCoins(home, cost);
            if (plan.made > 1) Crafts.giveBack(level, v, InterviewItems.LETTER_OF_APPLICATION.get(), plan.made - 1);
            letter = new ItemStack(InterviewItems.LETTER_OF_APPLICATION.get());
        }
        StationTask t = JobMarket.named(c.trade);
        Post p = Post.of(iv.post);
        StationTask postTrade = p == null ? null : p.tradeFor(iv.village);
        String why = InterviewScript.why(level, iv, c, f);
        c.letterWords = "I, " + c.name + (c.outside ? " of " + c.homeName : "") + ", would take the post of " + iv.title + ". "
            + (t == null || t == StationTask.NONE ? "I've no trade yet" : "I'm " + JobMarket.a(JobMarket.noun(t)) + ", level "
            + (postTrade != null && postTrade == t ? c.level : f.tradeLevel(t))) + "; " + c.age + " years old"
            + (c.knacks > 0 ? "; " + JobMarket.words(c.knacks) + (c.knacks == 1 ? " knack" : " knacks") + " of the trade" : "") + ". " + firstSentence(why);
        CompoundTag w = new CompoundTag();
        w.putString("Name", c.name);
        w.putString("From", c.homeName);
        w.putString("Post", iv.title);
        w.putString("Town", Villages.name(iv.village));
        w.putString("Trade", t == null || t == StationTask.NONE ? "" : t.title);
        w.putInt("Level", t == null || t == StationTask.NONE ? 0 : f.tradeLevel(t));
        w.putInt("Age", c.age);
        w.putInt("Knacks", c.knacks);
        w.putString("Why", firstSentence(why));
        w.putString("Words", c.letterWords);
        w.putLong("Day", day);
        w.putInt("Interview", iv.id);
        CompoundTag tag = letter.has(DataComponents.CUSTOM_DATA) ? letter.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        tag.put(LetterOfApplicationItem.KEY, w);
        letter.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        letter.set(DataComponents.CUSTOM_NAME, Component.literal(c.name + "'s Letter of Application"));
        Homes.keepsake(letter, f);
        if (fresh) {
            ItemStack left = f.insertItem(letter);
            if (!left.isEmpty()) return false;
        }
        c.letter = true;
        f.brain("wrote a letter of application for the post of " + iv.title);
        LOG.info("[MCA-INTERVIEW] {} wrote a letter of application ({}): {}", c.name, fresh ? "a paper and an ink sac out of "
            + Villages.name(home) + "'s stores" : "written over an old one", c.letterWords);
        return true;
    }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }

    /** The candidate's letter, wherever it carries it (its other hand, its pack), and which slot (-1: in its hand). */
    @Nullable
    private static int[] letterAt(VillageFolkEntity f, int interview) {
        if (isLetter(f.getOffhandItem(), interview)) return new int[]{ -1 };
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) if (isLetter(pack.get(i), interview)) return new int[]{ i };
        return null;
    }

    static boolean isLetter(ItemStack s, int interview) {
        return s.is(InterviewItems.LETTER_OF_APPLICATION.get()) && LetterOfApplicationItem.words(s).getInt("Interview") == interview;
    }

    /** Its letter into its other hand (what was there into its pack), if it is not there already. */
    private static void holdLetter(VillageFolkEntity f, int interview) {
        int[] at = letterAt(f, interview);
        if (at == null || at[0] < 0) return;
        ItemStack off = f.getOffhandItem();
        var pack = f.getInventoryItems();
        ItemStack letter = pack.get(at[0]);
        pack.set(at[0], Leisure.isProp(off) ? ItemStack.EMPTY : off);
        f.setItemSlot(EquipmentSlot.OFFHAND, letter);
    }

    /** A letter in its other hand back into its pack. */
    private static void pocketLetter(VillageFolkEntity f) {
        ItemStack off = f.getOffhandItem();
        if (!off.is(InterviewItems.LETTER_OF_APPLICATION.get())) return;
        f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ItemStack left = f.insertItem(off);
        if (!left.isEmpty()) f.setItemSlot(EquipmentSlot.OFFHAND, left);
    }

    // ------------------------------------------------------------------ the clock

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 7) return;
        com.jrpetty.mcassistant.Guard.run("interviews", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
        });
    }

    /** Once a second: each town's look over its posts once a day, and the interview on (or due) in it. */
    static void tick(ServerLevel level) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            UUID id = v.id();
            if (auto && t >= 1500L && t < 11000L && LOOKED.getOrDefault(id, -1L) < day && !Villages.folkOf(id).isEmpty()) {
                LOOKED.put(id, day);
                try {
                    daily(level, v, day);
                } catch (RuntimeException e) {
                    LOG.warn("[MCA-INTERVIEW] {}: the day's look failed: {}", Villages.name(id), e.toString());
                }
            }
            InterviewBook.Town town = InterviewBook.of().towns.get(id);
            if (town == null || town.list.isEmpty()) continue;
            step(level, v);
        }
        sweep(level);
    }

    /** A town's interviews a second on: the one running, else the next due begun, and those set made ready. */
    static void step(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Interview on = running(id);
        if (on != null) {
            run(level, v, on);
            return;
        }
        Interview next = null;
        for (Interview iv : coming(id)) {
            if (iv.stage != Stage.SET) continue;
            if (!still(level, iv)) continue;
            prepare(level, v, iv);
            if (next == null) next = iv;
        }
        if (next == null) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (day < next.dueDay || day == next.dueDay && t < next.dueTime) return;
        if (t < 1000L) return;                                                   // before dawn: it waits for the morning
        String no = notNow(level, v);
        if (no == null && t < LATEST) {
            begin(level, v, next);
            return;
        }
        // A raid, a fire or a storm: it waits while the morning lasts. A festival, or the morning gone: another day.
        if (no != null && t < LATEST && !no.equals("it was a festival")) return;
        putOff(level, v, next, no == null ? "the morning was gone" : no);
    }

    /** Why it cannot be held just now: the night, a raid, a fire, a thunderstorm, a festival; null if it can. */
    @Nullable
    static String notNow(ServerLevel level, Villages.Village v) {
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (t < 1000L || t >= 12000L) return "it was night";
        if (Raids.underAlarm(v.id())) return "the bell was ringing";
        if (FireBrigade.burningNow(v.id()) != null) return "the town was on fire";
        if (Weather.stormy(level)) return "a thunderstorm";
        if (!fairDay && Festivals.today(v.id(), day) != null) return "it was a festival";
        return null;
    }

    /** Put off a day (three times at most: then the panel decides on paper). */
    static void putOff(ServerLevel level, Villages.Village v, Interview iv, String why) {
        long day = level.getDayTime() / 24000L;
        iv.putOff++;
        release(level, iv);
        // Those come from away wait on for the new day (the inn takes them in).
        for (Cand c : iv.cands) if (c.outside && c.arrived && Civics.find(level, c.id) != null) ROLES.put(c.id, new Role(iv.village, iv.id));
        if (iv.putOff > 3) {
            Villages.tell(v.id(), day, "the interviews for " + iv.title + " were put off once too often (" + why + "): the panel chose on paper");
            for (Cand c : iv.cands) c.absent = c.absent || !c.seen;
            decideOnPaper(level, v, iv);
            return;
        }
        iv.stage = Stage.SET;
        iv.dueDay = Math.max(iv.dueDay, day) + 1;
        iv.dueTime = START;
        iv.current = -1;
        iv.lines.clear();
        iv.said.clear();
        for (Cand c : iv.cands) c.seen = false;
        InterviewBook.changed();
        Villages.tell(v.id(), day, "the interviews for " + iv.title + " were put off to day " + (iv.dueDay + 1) + ": " + why);
        LOG.info("[MCA-INTERVIEW] {}: {} put off to day {} ({})", Villages.name(v.id()), iv.title, iv.dueDay + 1, why);
    }

    /** Is the post still to be filled (a notice still up, its candidates still the town's or its applicants)? */
    static boolean still(ServerLevel level, Interview iv) {
        if (iv.opening >= 0) {
            JobMarket.Opening o = JobMarket.opening(iv.village, iv.opening);
            if (o == null || o.state() != JobMarket.State.OPEN) {
                cancel(level, iv, o == null ? "the notice came down" : "the place was " + (o.by().isEmpty() ? "no longer wanted" : "filled by " + o.by()));
                return false;
            }
        }
        int left = 0;
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null) {
                if (f.ownerId() != null && f.ownerId().equals(c.home)) left++;           // (not moved away, married away)
            } else if (c.outside || !gone(iv.village, c.id)) {
                left++;                                                               // asleep with its ground, or away
            }
        }
        if (left == 0) {
            cancel(level, iv, "nobody was left to stand");
            return false;
        }
        return true;
    }

    /** No longer on the town's roll (dead, gone). */
    private static boolean gone(UUID village, UUID folk) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(folk)) return false;
        return Villages.headcount(village) > 0 && Villages.loadedCount(village) >= Villages.headcount(village);
    }

    static void cancel(ServerLevel level, Interview iv, String why) {
        if (!iv.stage.open()) return;
        iv.stage = Stage.CANCELLED;
        iv.reason = why;
        iv.decidedDay = level.getDayTime() / 24000L;
        release(level, iv);
        InterviewBook.town(iv.village).quiet.put(iv.post, iv.decidedDay);
        InterviewBook.changed();
        Villages.tell(iv.village, iv.decidedDay, "the interviews for " + iv.title + " were called off: " + why);
        LOG.info("[MCA-INTERVIEW] {}: {} called off ({})", Villages.name(iv.village), iv.title, why);
    }

    /** The day before: the table set out, the candidates' letters, and those from away set off in time. */
    static void prepare(ServerLevel level, Villages.Village v, Interview iv) {
        if (iv.table == null) iv.table = InterviewTable.ready(level, v);
        long dt = level.getDayTime();
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f == null) continue;
            if (!c.letter) write(level, iv, c, f);
            if (c.outside && !c.setOff && dt >= c.leaveAt) setOff(level, v, iv, c, f);
        }
    }

    // ------------------------------------------------------------------ the road (one from another town)

    /** On the road to its interview, or home from it. */
    static final class Visit {
        final UUID village;
        final int interview;
        final boolean home;
        final List<BlockPos> way;
        int at, walkTick = -1000, gainedTick, spokeTick;
        double best = Double.MAX_VALUE;
        long lastStep;
        @Nullable BlockPos window;

        Visit(UUID village, int interview, boolean home, List<BlockPos> way) {
            this.village = village;
            this.interview = interview;
            this.home = home;
            this.way = way;
        }
    }

    /** One from another town sets off along the road (the caravans' way) to its interview. */
    static void setOff(ServerLevel level, Villages.Village to, Interview iv, Cand c, VillageFolkEntity f) {
        Villages.Village from = c.home == null ? null : Villages.get(c.home);
        if (from == null || !from.dim().equals(to.dim()) || JobSeekers.busy(f) || f.trip() != null || f.expedition() != null) return;
        c.setOff = true;
        List<BlockPos> way = new ArrayList<>(Caravans.way(from, to));
        way.add(meet(level, to, iv));
        Visit vis = new Visit(to.id(), iv.id, false, way);
        vis.gainedTick = f.tickCount;
        vis.lastStep = level.getGameTime();
        VISITS.put(f.getUUID(), vis);
        f.clearQueue();
        f.getNavigation().stop();
        if (f.isSleeping()) f.stopSleeping();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off to " + Villages.name(to.id()) + " for my interview. Wish me luck!",
            "Letter? Yes. Clean boots? Yes. Off to " + Villages.name(to.id()) + ", then."));
        f.brain("on the road to " + Villages.name(to.id()) + " for an interview");
        InterviewBook.changed();
        LOG.info("[MCA-INTERVIEW] {} of {} sets off for its interview in {} ({} waypoints)", c.name, c.homeName, Villages.name(to.id()), way.size());
    }

    /** Where one from away waits for its interview: by the town's board, or the heart. */
    static BlockPos meet(ServerLevel level, Villages.Village v, Interview iv) {
        InterviewTable.Table t = iv.table != null ? iv.table : InterviewTable.known(v.id());
        if (t != null) return t.out();
        BlockPos b = VillageBoards.lectern(v.id());
        return b != null ? b : v.centre();
    }

    /** Is it on the road for an interview, or waiting about a town that is not its own for one (the inn takes it in)? */
    public static boolean visiting(VillageFolkEntity f) {
        if (VISITS.containsKey(f.getUUID())) return true;
        Role r = ROLES.get(f.getUUID());
        return r != null && f.ownerId() != null && !f.ownerId().equals(r.village());
    }

    private static UUID owner(UUID folk) {
        return UUID.nameUUIDFromBytes(("mca-interview-" + folk).getBytes());
    }

    /** A step along the road: as a hired folk walks to its new town (JobSeekers), the ground round it kept awake. */
    private static boolean travel(VillageFolkEntity f, ServerLevel level, Visit vis) {
        vis.lastStep = level.getGameTime();
        if (f.isSleeping()) f.stopSleeping();
        BlockPos here = f.blockPosition();
        if (vis.window == null || vis.window.distSqr(here) >= 16 * 16) {
            if (vis.window != null) ChunkLoad.setLoaded(level, owner(f.getUUID()), vis.window, 1, false);
            ChunkLoad.setLoaded(level, owner(f.getUUID()), here, 1, true);
            vis.window = here.immutable();
        }
        if (vis.at >= vis.way.size()) {
            arrive(level, f, vis);
            return false;
        }
        BlockPos p = vis.way.get(vis.at);
        BlockPos onGround = level.hasChunk(p.getX() >> 4, p.getZ() >> 4)
            ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p) : p;
        double dx = f.getX() - (onGround.getX() + 0.5), dz = f.getZ() - (onGround.getZ() + 0.5), d = dx * dx + dz * dz;
        if (d <= 9.0) {
            vis.at++;
            vis.best = Double.MAX_VALUE;
            vis.gainedTick = f.tickCount;
            return true;
        }
        if (d < vis.best - 1.0) {
            vis.best = d;
            vis.gainedTick = f.tickCount;
        }
        if (f.getNavigation().isDone() || f.tickCount - vis.walkTick > 80) {
            f.walkTo(onGround, 0.9D);
            vis.walkTick = f.tickCount;
        }
        // No nearer for half a minute (a river with no bridge, a cliff): set down at the next step, as a traveller is.
        if (f.tickCount - vis.gainedTick > (hurry ? 200 : 600)) {
            f.moveTo(onGround.getX() + 0.5, onGround.getY(), onGround.getZ() + 0.5, f.getYRot(), 0.0F);
            f.getNavigation().stop();
            vis.gainedTick = f.tickCount;
            vis.best = Double.MAX_VALUE;
        }
        if (f.tickCount - vis.spokeTick > 2400) {
            vis.spokeTick = f.tickCount;
            FolkTalk.speak(f, vis.home ? "Home again. What a day." : FolkTalk.pick(f.getRandom(), "On my way to " + Villages.name(vis.village)
                + " for my interview.", "I'm going over what I'll say. Again."));
        }
        f.hobbyNow = vis.home ? "on the road home from an interview" : "on the road to an interview in " + Villages.name(vis.village);
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** At the end of the road: at the town for its interview (paid its room at the inn), or home again. */
    private static void arrive(ServerLevel level, VillageFolkEntity f, Visit vis) {
        VISITS.remove(f.getUUID());
        if (vis.window != null) ChunkLoad.setLoaded(level, owner(f.getUUID()), vis.window, 1, false);
        f.getNavigation().stop();
        WALKS.remove(f.getUUID());
        if (vis.home) {
            f.brain("home from its interview");
            return;
        }
        Interview iv = find(vis.village, vis.interview);
        Cand c = iv == null ? null : iv.cand(f.getUUID());
        if (iv == null || c == null || !iv.stage.open()) return;
        c.arrived = true;
        ROLES.put(f.getUUID(), new Role(vis.village, vis.interview));
        // The town that asked it here pays its room and supper at the inn, if it has one.
        if (Inn.inn(vis.village) != null && Ledger.coins(vis.village) >= Inn.ROOM + 1) {
            int paid = Ledger.takeCoins(vis.village, Inn.ROOM + 1);
            if (paid > 0) f.earn(paid);
        }
        InterviewBook.changed();
        FolkTalk.speak(f, "Hello! I'm " + c.name + ", come from " + c.homeName + " for the interview" + (iv.title.isEmpty() ? "" : " — the " + iv.title + "'s post") + ".");
        f.brain("arrived in " + Villages.name(vis.village) + " for its interview");
        LOG.info("[MCA-INTERVIEW] {} of {} arrived in {} for its interview", c.name, c.homeName, Villages.name(vis.village));
    }

    /** Home again after its interview, by the road it came. */
    private static void goHome(ServerLevel level, VillageFolkEntity f, Interview iv) {
        UUID home = f.ownerId();
        Villages.Village from = Villages.get(iv.village), to = home == null ? null : Villages.get(home);
        if (from == null || to == null || home.equals(iv.village) || !from.dim().equals(to.dim())) return;
        List<BlockPos> way = new ArrayList<>(Caravans.way(from, to));
        Visit vis = new Visit(home, iv.id, true, way);
        vis.gainedTick = f.tickCount;
        vis.lastStep = level.getGameTime();
        VISITS.put(f.getUUID(), vis);
    }

    /** Journeys whose traveller has not been seen for a minute: let go, and the ground round it. */
    private static void sweep(ServerLevel level) {
        long now = level.getGameTime();
        for (Map.Entry<UUID, Visit> e : VISITS.entrySet()) {
            Visit vis = e.getValue();
            if (now - vis.lastStep < 1200L && now >= vis.lastStep) continue;
            if (Civics.find(level, e.getKey()) != null) continue;
            Villages.Village v = Villages.get(vis.village);
            if (v != null && !v.dim().equals(level.dimension())) continue;
            VISITS.remove(e.getKey());
            if (vis.window != null) ChunkLoad.setLoaded(level, owner(e.getKey()), vis.window, 1, false);
        }
    }

    // ------------------------------------------------------------------ the interview, a second at a time

    /** The day come: the panel to its chairs, the candidates to the bench (those not here marked absent). */
    static void begin(ServerLevel level, Villages.Village v, Interview iv) {
        UUID id = v.id();
        long gt = level.getGameTime();
        iv.table = InterviewTable.ready(level, v);
        if (iv.table == null) {
            InterviewTable.Table plan = InterviewTable.layout(level, v);
            if (plan == null) {
                putOff(level, v, iv, "there was nowhere to hold it");
                return;
            }
            iv.table = new InterviewTable.Table(plan.where(), false, plan.panel(), plan.cand(), plan.bench(), plan.out(), plan.huddle(),
                plan.aside(), plan.front(), false);                         // no hand free to set the chairs out: held standing
        }
        panel(level, v, iv);
        iv.where = iv.table.where();
        iv.stage = Stage.GATHERING;
        iv.stageAt = gt;
        iv.current = -1;
        iv.step = 0;
        iv.lines.clear();
        iv.said.clear();
        iv.greeted.clear();
        iv.sat.clear();
        iv.shaken = false;
        iv.waitingFor = null;
        iv.waitSince = 0;
        iv.lastSpeaker = null;
        iv.chatterAt = gt + (hurry ? 60 : 400);
        for (Cand c : iv.cands) {
            c.seen = false;
            VillageFolkEntity f = Civics.find(level, c.id);
            c.absent = f == null || !InterviewPosts.about(f) && !ROLES.containsKey(c.id)
                || c.outside && !c.arrived && f.distanceToSqr(Vec3.atCenterOf(v.centre())) > 64 * 64;
            if (!c.absent) {
                ROLES.put(c.id, new Role(id, iv.id));
                VISITS.remove(c.id);
            }
        }
        for (UUID p : new UUID[]{ iv.chair, iv.master, iv.councillor }) if (p != null) ROLES.put(p, new Role(id, iv.id));
        InterviewBook.changed();
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        if (chair != null) FolkTalk.speak(chair, FolkTalk.pick(level.getRandom(), "To " + iv.where + ", all of you: the interviews for " + iv.title + ".",
            "Right — the interviews. " + capital(iv.title) + ". To " + iv.where + "."));
        Raids.tellNear(level, v.centre(), 96, Component.literal("Interviews are beginning at " + iv.where + " in " + Villages.name(id) + ": the post of "
            + iv.title + ". Come and watch.").withStyle(ChatFormatting.GOLD), false);
        LOG.info("[MCA-INTERVIEW] {}: the interviews for {} begin at {} ({} candidates, {} here)", Villages.name(id), iv.title, iv.where,
            iv.cands.size(), iv.cands.stream().filter(c -> !c.absent).count());
    }

    /** The running interview's next second. */
    static void run(ServerLevel level, Villages.Village v, Interview iv) {
        long gt = level.getGameTime();
        if (iv.stage == Stage.AWAITING) {
            awaiting(level, v, iv);
            return;
        }
        String no = notNow(level, v);
        if (no != null && !no.equals("it was night")) {
            VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
            if (chair != null) FolkTalk.speak(chair, "We'll stop there — " + no + ". We'll finish another day.");
            putOff(level, v, iv, no);
            return;
        }
        greet(level, iv);
        if (iv.stage == Stage.GATHERING) {
            gather(level, v, iv, gt);
            return;
        }
        chatter(level, iv, gt);
        if (gt < iv.nextAt) return;
        if (iv.waitingFor != null && !ready(level, iv, gt)) return;
        iv.waitingFor = null;
        if (iv.step < iv.lines.size()) {
            say(level, v, iv, iv.lines.get(iv.step++), gt);
            return;
        }
        switch (iv.stage) {
            case SITTING -> nextCandidate(level, v, iv, gt);
            case CONFERRING -> confer(level, v, iv, gt);
            case ANNOUNCING -> conclude(level, v, iv);
            default -> { }
        }
    }

    /** The panel and the candidates come to their places; then the chair opens. */
    private static void gather(ServerLevel level, Villages.Village v, Interview iv, long gt) {
        InterviewTable.Table t = iv.table;
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        boolean chairReady = chair == null || near(chair, InterviewTable.spot(t, t.chairSeat()), 1.6);
        int seated = 0, here = 0;
        for (Cand c : iv.cands) {
            if (c.absent) continue;
            here++;
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null && near(f, InterviewTable.spot(t, bench(iv, c)), 1.6)) seated++;
        }
        long waited = gt - iv.stageAt;
        if (!(chairReady && seated >= here) && waited < waitMost(900) && !(chairReady && waited > waitMost(600))) return;
        iv.stage = Stage.SITTING;
        iv.stageAt = gt;
        iv.lines.clear();
        iv.step = 0;
        List<String> here2 = new ArrayList<>(), away = new ArrayList<>();
        for (Cand c : iv.cands) (c.absent ? away : here2).add(c.name);
        String opener = (iv.playerChairs ? iv.chairName + " chairs today, and I put the questions in its name. " : "")
            + "We're here for the post of " + iv.title + ". " + capital(JobMarket.words(iv.cands.size())) + " stand: "
            + JobMarket.join(names(iv)) + ".";
        iv.lines.add(Line.of("chair", opener));
        String sits = "";
        if (!iv.masterName.isEmpty()) sits = iv.masterName + " sits with me" + (iv.councillorName.isEmpty() ? "" : ", and " + iv.councillorName + " for the council");
        else if (!iv.councillorName.isEmpty()) sits = iv.councillorName + " sits with me for the council";
        if (iv.guest != null) sits += (sits.isEmpty() ? "" : "; ") + iv.guestName + " joins us, as a friend of the town";
        if (!sits.isEmpty()) iv.lines.add(Line.of("chair", sits + ". We'll see each of you in turn."));
        if (!away.isEmpty()) iv.lines.add(Line.of("chair", JobMarket.join(away) + " couldn't be here: we'll hear "
            + (away.size() == 1 ? "its letter" : "their letters") + " in turn."));
        iv.nextAt = gt;
    }

    /** The next candidate across the table; after the last, the candidates out and the panel into a huddle. */
    private static void nextCandidate(ServerLevel level, Villages.Village v, Interview iv, long gt) {
        if (iv.current >= 0 && iv.current < iv.cands.size()) iv.cands.get(iv.current).seen = true;
        iv.current++;
        iv.step = 0;
        iv.lines.clear();
        if (iv.current < iv.cands.size()) {
            Cand c = iv.cands.get(iv.current);
            VillageFolkEntity f = c.absent ? null : Civics.find(level, c.id);
            if (f != null && f.distanceToSqr(Vec3.atCenterOf(iv.table.cand().pos())) > 32 * 32) {
                f = null;                                                      // never came to the table (called away): its letter
                c.absent = true;
                ROLES.remove(c.id);
            }
            if (f != null) {
                Set<UUID> not = new HashSet<>();
                for (Cand o : iv.cands) not.add(o.id);
                for (UUID p : new UUID[]{ iv.chair, iv.master, iv.councillor }) if (p != null) not.add(p);
                c.words.removeIf(r -> !r.player);
                c.words.addAll(0, InterviewScript.referees(level, iv, f, not));
            }
            boolean first = true;
            for (int i = 0; i < iv.current; i++) if (!iv.cands.get(i).absent) first = false;
            iv.lines.addAll(InterviewScript.turn(level, iv, c, f, first));
            iv.nextAt = gt + 10;
            InterviewBook.changed();
            return;
        }
        iv.stage = Stage.CONFERRING;
        iv.stageAt = gt;
        iv.current = -1;
        iv.lines.add(Line.of("chair", FolkTalk.pick(level.getRandom(), "Thank you, all. If you'd step out a moment while we talk it over.",
            "That's everybody. Wait outside, would you? We'll call you back.")));
        iv.lines.addAll(InterviewScript.huddle(iv, level.getRandom()));
        iv.nextAt = gt + line();
    }

    /** The huddle done: the choice made (or, for a player who leads, waited for), and the candidates called back. */
    private static void confer(ServerLevel level, Villages.Village v, Interview iv, long gt) {
        for (Cand c : iv.cands) c.total = c.paper + c.interview();
        UUID leader = iv.playerChairs ? PlayerLeader.leaderId(iv.village) : null;
        if (leader != null && !iv.chose.containsKey(leader)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(leader);
            boolean by = p != null && p.level() == level && p.distanceToSqr(Vec3.atCenterOf(iv.table.chairSeat().pos())) < 32 * 32;
            if (by && gt - iv.stageAt < waitMost(2400)) {
                if (iv.waitSince == 0) {
                    iv.waitSince = gt;
                    try {
                        page(p);
                    } catch (RuntimeException e) {
                        LOG.warn("[MCA-INTERVIEW] the interview page could not be sent: {}", e.toString());
                    }
                    p.sendSystemMessage(Component.literal("The panel has heard them all. Yours is the choice: use the page, or /village interviews choose <name>.")
                        .withStyle(ChatFormatting.GOLD));
                }
                return;
            }
            if (!by) {
                // Not here: the choice waits on the leader till the day's end; the candidates go back to their day.
                iv.stage = Stage.AWAITING;
                iv.stageAt = gt;
                release(level, iv);
                VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
                if (chair != null) FolkTalk.speak(chair, iv.chairName + " will choose. You'll hear by sundown.");
                if (p != null) p.sendSystemMessage(Component.literal(Villages.name(iv.village) + " has interviewed for " + iv.title
                    + ": the choice is yours by sundown (/village interviews page), or the panel's.").withStyle(ChatFormatting.GOLD));
                InterviewBook.changed();
                return;
            }
        }
        announce(level, v, iv, gt);
    }

    /** A player who leads has the choice till the day's end; then (or once it chooses) it is told. */
    private static void awaiting(ServerLevel level, Villages.Village v, Interview iv) {
        UUID leader = PlayerLeader.leaderId(iv.village);
        long t = level.getDayTime() % 24000L;
        if (leader != null && !iv.chose.containsKey(leader) && t < 12000L && level.getDayTime() / 24000L <= iv.dueDay) return;
        Cand w = choose(level, iv);
        finish(level, v, iv, w, false);
    }

    /** The panel's choice: the player who leads has it; else each on the panel (and a guest) votes, the chair breaking a tie. */
    static Cand choose(ServerLevel level, Interview iv) {
        for (Cand c : iv.cands) c.total = c.paper + c.interview();
        UUID leader = iv.playerChairs ? PlayerLeader.leaderId(iv.village) : null;
        if (leader != null) {
            UUID pick = iv.chose.get(leader);
            if (pick != null) {
                Cand c = iv.cand(pick);
                if (c != null) return c;
            }
        }
        Map<UUID, Integer> votes = new LinkedHashMap<>();
        Cand chairPick = null;
        List<UUID> panel = new ArrayList<>();
        if (iv.chair != null) panel.add(iv.chair);
        if (iv.master != null) panel.add(iv.master);
        if (iv.councillor != null) panel.add(iv.councillor);
        for (UUID m : panel) {
            VillageFolkEntity f = Civics.find(level, m);
            Cand best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Cand c : iv.cands) {
                int lean = f == null ? 0 : Math.max(-2, Math.min(2, f.life().affinity(c.id) / 15));
                int s = c.total + lean;
                if (s > bestScore) { bestScore = s; best = c; }
            }
            if (best == null) continue;
            votes.merge(best.id, 1, Integer::sum);
            if (m.equals(iv.chair)) chairPick = best;
        }
        if (iv.guest != null && iv.chose.containsKey(iv.guest)) votes.merge(iv.chose.get(iv.guest), 1, Integer::sum);
        Cand top = null;
        int most = -1;
        for (Cand c : iv.cands) {
            int n = votes.getOrDefault(c.id, 0);
            if (n > most || n == most && top != null && (chairPick == c || chairPick != top && c.total > top.total)) {
                most = n;
                top = c;
            }
        }
        if (most <= 0) {
            for (Cand c : iv.cands) if (top == null || c.total > top.total) top = c;
        }
        return top;
    }

    /** The candidates called back, and the choice told: why in a sentence, the hand shaken, the others thanked. */
    private static void announce(ServerLevel level, Villages.Village v, Interview iv, long gt) {
        Cand w = choose(level, iv);
        Cand next = null;
        for (Cand c : iv.cands) if (c != w && (next == null || c.total > next.total)) next = c;
        iv.winner = w.id;
        iv.winnerName = w.name;
        iv.reason = InterviewScript.reason(iv, w, next);
        iv.close = next != null && Math.abs(w.total - next.total) <= 6;
        iv.stage = Stage.ANNOUNCING;
        iv.stageAt = gt;
        iv.lines.clear();
        iv.step = 0;
        boolean leaderChose = iv.playerChairs && iv.chose.containsKey(PlayerLeader.leaderId(iv.village));
        if (leaderChose && w != choose(level, iv.panelOnly())) iv.reason = iv.chairName + "'s own choice, over the panel's";
        else if (leaderChose) iv.reason = iv.chairName + "'s choice, and the panel's: " + iv.reason;
        iv.lines.add(Line.of("chair", FolkTalk.pick(level.getRandom(), "Come back in, all of you.", "Thank you for waiting. Come in.")));
        iv.lines.add(Line.of("chair", (leaderChose ? iv.chairName + " has chosen. " : "We've decided. ") + "The post of " + iv.title + " goes to "
            + w.name + ": " + iv.reason + "."));
        iv.lines.add(new Line("cand:" + w.id, "", Act.SHAKE, null));
        VillageFolkEntity wf = Civics.find(level, w.id);
        String thanks = "Thank you! I won't let you down.";
        if (wf != null) {
            if (wf.life().has(Social.Trait.SHY)) thanks = "Me? Oh — thank you. Thank you.";
            else if (wf.life().has(Social.Trait.GRUMPY)) thanks = "About time somebody noticed. Thank you.";
            else if (proud(wf)) thanks = "I knew it. Thank you, all.";
            else if (wf.life().has(Social.Trait.CHEERFUL)) thanks = "Oh! Oh, thank you! You won't regret it!";
        }
        iv.lines.add(Line.of("cand:" + w.id, thanks));
        for (Cand c : iv.cands) {
            if (c == w || c.absent) continue;
            c.why = InterviewScript.kindly(c, w);
            iv.lines.add(Line.of("chair", c.name + ", thank you: " + c.why + "."));
            VillageFolkEntity cf = Civics.find(level, c.id);
            if (cf != null && level.getRandom().nextInt(3) > 0) {
                iv.lines.add(Line.of("cand:" + c.id, cf.life().has(Social.Trait.GRUMPY) ? "Hmph. We'll see."
                    : cf.life().has(Social.Trait.SHY) ? "…thank you." : FolkTalk.pick(level.getRandom(), "Thank you. I'll be back.",
                    "Fair enough. Congratulations, " + w.name + ".")));
            }
        }
        iv.nextAt = gt + line();
        InterviewBook.changed();
    }

    /** The last line said: everything done (the post given, the books written, everybody back to its day). */
    private static void conclude(ServerLevel level, Villages.Village v, Interview iv) {
        Cand w = iv.winner == null ? null : iv.cand(iv.winner);
        if (w == null) w = choose(level, iv);
        finish(level, v, iv, w, true);
    }

    /** On paper, with no interview to be had (put off too often): the paper and nothing else. */
    static void decideOnPaper(ServerLevel level, Villages.Village v, Interview iv) {
        for (Cand c : iv.cands) {
            c.nerves = c.prep = c.answers = c.evidence = c.honesty = c.boast = 0;
            c.total = c.paper + c.refs;
        }
        Cand w = null;
        for (Cand c : iv.cands) if (w == null || c.total > w.total) w = c;
        if (w == null) {
            cancel(level, iv, "nobody stood");
            return;
        }
        Cand next = null;
        for (Cand c : iv.cands) if (c != w && (next == null || c.total > next.total)) next = c;
        iv.winner = w.id;
        iv.winnerName = w.name;
        iv.reason = InterviewScript.reason(iv, w, next);
        for (Cand c : iv.cands) if (c != w) c.why = InterviewScript.kindly(c, w);
        finish(level, v, iv, w, false);
    }

    /**
     * Done: the post given, the chronicle and every card told, the others' spirits and their week of work after, the
     * friends' words, and everybody let go (those from away off home).
     */
    static void finish(ServerLevel level, Villages.Village v, Interview iv, Cand w, boolean present) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        for (Cand c : iv.cands) if (c.total == 0) c.total = c.paper + c.interview();
        iv.winner = w.id;
        iv.winnerName = w.name;
        if (iv.reason.isEmpty()) {
            Cand next = null;
            for (Cand c : iv.cands) if (c != w && (next == null || c.total > next.total)) next = c;
            iv.reason = InterviewScript.reason(iv, w, next);
        }
        iv.stage = Stage.DONE;
        iv.decidedDay = day;
        Post post = Post.of(iv.post);
        String town = Villages.name(id);
        List<String> others = new ArrayList<>();
        for (Cand c : iv.cands) if (c != w) others.add(c.name);
        String took;
        if (post != null && post.kind() == InterviewPosts.Kind.OPENING) took = fillOpening(level, v, iv, w);
        else {
            VillageFolkEntity f = Civics.find(level, w.id);
            InterviewBook.town(id).held.put(iv.post, new String[]{ w.id.toString(), w.name, Long.toString(day) });
            took = f == null || post == null ? "" : InterviewPosts.give(level, v, post, f);
        }
        w.outcome = "chosen";
        w.why = iv.reason;
        for (Cand c : iv.cands) {
            if (c != w) {
                c.outcome = "not chosen";
                if (c.why.isEmpty()) c.why = InterviewScript.kindly(c, w);
            }
        }
        // The books: the chronicle, every card, the others' spirits and their week's hard work.
        Villages.tell(id, day, w.name + (w.outside ? " of " + w.homeName : "") + " got the post of " + iv.title
            + (others.isEmpty() ? "" : " after " + (iv.close ? "a close interview" : "an interview") + " with " + JobMarket.join(others))
            + ": " + iv.reason + (took.isEmpty() ? "" : "; " + w.name + " " + took));
        for (Cand c : iv.cands) {
            boolean won = c == w;
            card(c.id, day, "Interviewed for " + iv.title + (c.outside ? " in " + town : "") + " on day " + (day + 1) + ": "
                + (won ? "chosen" : "not chosen — " + c.why));
            InterviewBook.of().after.put(c.id, new long[]{ day, won ? 1 : 0 });
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f == null) continue;
            f.persona().remember(day, won ? "I got the post of " + iv.title + " at interview in " + town
                : "I was interviewed for " + iv.title + " in " + town + " and not chosen: " + c.why, won ? 7 : -2);
            f.refreshMood();
        }
        InterviewBook.changed();
        if (present) {
            congratulate(level, iv, w);
            for (Cand c : iv.cands) if (c != w && !c.absent) console(level, iv, c);
        } else {
            // Chosen with the candidates gone back to their day: the chair says so where it is, and the chosen hears it.
            VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
            if (chair != null) FolkTalk.speak(chair, (iv.playerChairs ? iv.chairName + " has chosen" : "The panel has chosen") + ": "
                + w.name + " is our " + iv.title + ".");
            VillageFolkEntity wf = Civics.find(level, w.id);
            if (wf != null) {
                wf.sayLater(FolkTalk.pick(wf.getRandom(), "I've got it! The " + iv.title + "'s post!", "Me? They chose me!"), 40);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, wf.getX(), wf.getY() + 2.0, wf.getZ(), 12, 0.5, 0.4, 0.5, 0.05);
            }
            for (Cand c : iv.cands) if (c != w && !c.outside) JobSeekers.told(c.id, "Not chosen for " + iv.title + ": " + c.why + ". I'll work on it.");
            Raids.tellNear(level, v.centre(), 96, Component.literal(w.name + " got the post of " + iv.title + " in " + town + ": " + iv.reason + ".")
                .withStyle(ChatFormatting.GOLD), false);
        }
        // Some of the others go to the board for a place elsewhere.
        for (Cand c : iv.cands) {
            if (c == w || c.outside) continue;
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null && (f.persona().ambition() == Persona.Ambition.MASTER || Values.top(f) == Values.Value.WEALTH)) JobSeekers.lookNow(f);
        }
        release(level, iv);
        LOG.info("[MCA-INTERVIEW] {}: {} got the post of {} ({}); scores {}", town, w.name, iv.title, iv.reason, scores(iv));
    }

    /** "Ada 54 (paper 46, +8), Bram 50 (paper 52, -2)". */
    static String scores(Interview iv) {
        List<String> out = new ArrayList<>();
        for (Cand c : iv.cands) out.add(c.name + " " + c.total + " (paper " + c.paper + ", " + (c.interview() >= 0 ? "+" : "") + c.interview() + ")");
        return String.join(", ", out);
    }

    /**
     * A notice's interview decided: the chosen taken on (from another town: its offer waits for it at home, where it goes
     * first to fetch its things and its family; one of our own: it takes up the trade at once), the others told no, kindly.
     * Says what was done, in words.
     */
    private static String fillOpening(ServerLevel level, Villages.Village v, Interview iv, Cand w) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<JobMarket.Opening> ops = JobMarket.openings(id);
        JobMarket.Opening o = null;
        for (JobMarket.Opening x : ops) if (x.id == iv.opening) o = x;
        if (o == null || o.state() != JobMarket.State.OPEN) return "";
        List<JobMarket.Application> apps = JobMarket.applications(id);
        VillageFolkEntity elder = Orders.elderOf(id);
        String judge = iv.playerChairs ? iv.chairName : elder != null ? Leader.leaderName(id, elder) : "the panel";
        String did;
        if (w.outside) {
            JobMarket.Application a = null;
            for (JobMarket.Application x : apps) if (x.opening == o.id && x.folk.equals(w.id) && x.verdict == JobMarket.Verdict.WAITING) a = x;
            if (a == null) return "";
            List<String> good = new ArrayList<>(List.of("chosen at interview: " + iv.reason));
            // (No live folk handed on: its offer waits in its own town's notes for it to come home and go.)
            JobMarket.hire(level, v, o, a, null, good, iv.cands.size(), judge, null, day);
            did = "goes home to fetch its things, and comes back for good";
        } else {
            o.state = JobMarket.State.FILLED;
            o.by = w.name;
            o.from = Villages.name(id);
            o.closed = day;
            o.note = "chosen at interview from the town's own";
            VillageFolkEntity f = Civics.find(level, w.id);
            StationTask t = o.task();
            if (f != null && t != null) {
                BlockPos at = Villages.builtAt(id, workplaceOf(t));
                f.setWorkZone(null);
                f.setStation(at != null ? at : f.blockPosition(), t);
                f.setAutonomous(true);
                f.brain("took up " + t.label + " after its interview");
            }
            did = "took up the work at once";
        }
        for (JobMarket.Application a : apps) {
            if (a.opening != o.id || a.verdict != JobMarket.Verdict.WAITING || a.folk.equals(w.id)) continue;
            Cand c = iv.cand(a.folk);
            String why = c == null ? "the place went to " + w.name : "interviewed: " + (c.why.isEmpty() ? InterviewScript.kindly(c, w) : c.why);
            JobMarket.refuse(level, id, o, a, false, why, w.name, day);
            JobSeekers.told(a.folk, FolkTalk.pick(level.getRandom(), "Not this time. " + w.name + " got it. They told me why, kindly: I'll work on it.",
                "It went to " + w.name + ". Fair enough. I know what to do better now."));
        }
        JobMarket.openings(id, ops);
        JobMarket.applications(id, apps);
        return did;
    }

    /** The building a trade works in, for a new keeper's station. */
    private static String workplaceOf(StationTask t) {
        return switch (t) {
            case SMITH -> "smithy";
            case COOK -> "cafe";
            case SHOP -> "shop";
            case BREW -> "brewery";
            case ENCHANT -> "library";
            case TAILOR -> "workshop";
            case BANK -> "bank";
            default -> "";
        };
    }

    /** The chosen's friends and family about congratulate it. */
    private static void congratulate(ServerLevel level, Interview iv, Cand w) {
        VillageFolkEntity f = Civics.find(level, w.id);
        if (f == null) return;
        int n = 0;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(24.0),
                o -> o != f && o.isAlive() && !o.isBaby() && !o.isSleeping())) {
            if (n >= 3) break;
            boolean kin = w.id.equals(o.life().partner()) || Homes.childOf(o, f) || Homes.childOf(f, o);
            if (!kin && o.life().affinity(w.id) < Social.FRIEND) continue;
            o.sayLater(kin ? FolkTalk.pick(o.getRandom(), "That's my " + (w.id.equals(o.life().partner()) ? "love" : "family") + "! Well done, " + w.name + "!",
                "I knew you'd do it, " + w.name + "!") : FolkTalk.pick(o.getRandom(), "Well done, " + w.name + "!", "Congratulations, " + w.name
                + " — you earned it."), 40 + 25 * n);
            n++;
        }
    }

    /** One not chosen: a friend about consoles it. */
    private static void console(ServerLevel level, Interview iv, Cand c) {
        VillageFolkEntity f = Civics.find(level, c.id);
        if (f == null) return;
        UUID best = f.life().bestFriend();
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(24.0),
                o -> o != f && o.isAlive() && !o.isBaby() && !o.isSleeping())) {
            if (!o.getUUID().equals(best) && !c.id.equals(o.life().partner()) && o.life().affinity(c.id) < Social.CLOSE) continue;
            o.sayLater(FolkTalk.pick(o.getRandom(), "Chin up, " + c.name + ". Their loss.", "Next time, " + c.name + ". You'll have it next time.",
                "Come on, " + c.name + " — I'll buy you a drink."), 120);
            o.life().feel(c.id, c.name, 2);
            return;
        }
    }

    // ------------------------------------------------------------------ a line said

    /** One line: its act done first (a walk, the letter, a piece held up, a taste, a face), then said aloud. */
    private static void say(ServerLevel level, Villages.Village v, Interview iv, Line l, long gt) {
        VillageFolkEntity who = speaker(level, iv, l.who());
        Cand c = iv.now();
        VillageFolkEntity cf = c == null ? null : Civics.find(level, c.id);
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        switch (l.act()) {
            case CALL -> iv.waitingFor = "seat";
            case LETTER -> {
                if (cf != null && chair != null) {
                    int[] at = letterAt(cf, iv.id);
                    if (at != null) {
                        ItemStack letter = at[0] < 0 ? cf.getOffhandItem() : cf.getInventoryItems().get(at[0]);
                        if (at[0] < 0) cf.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                        else cf.getInventoryItems().set(at[0], ItemStack.EMPTY);
                        ItemStack off = chair.getOffhandItem();
                        if (!off.isEmpty() && !Leisure.isProp(off)) chair.insertItem(off);
                        chair.setItemSlot(EquipmentSlot.OFFHAND, letter);
                        iv.letterWith = chair.getUUID();
                        cf.swing(InteractionHand.OFF_HAND);
                    }
                }
            }
            case READ -> {
                if (chair != null) {
                    chair.swing(InteractionHand.OFF_HAND);
                    level.playSound(null, chair.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0F, 1.0F);
                }
            }
            case SHOW -> {
                if (cf != null) show(level, iv, cf);
            }
            case TASTE -> {
                if (cf != null && who != null) {
                    ItemStack food = InterviewScript.taste(cf);
                    if (!food.isEmpty()) {
                        ItemStack bite = food.split(1);
                        who.setItemSlot(EquipmentSlot.OFFHAND, bite);
                        who.swing(InteractionHand.OFF_HAND);
                        level.playSound(null, who.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.0F);
                        level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, bite), who.getX(), who.getEyeY() - 0.1,
                            who.getZ(), 6, 0.1, 0.05, 0.1, 0.02);
                        who.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);          // eaten
                    }
                }
            }
            case REF_COME -> {
                UUID ref = l.who().startsWith("folk:") ? parse(l.who().substring(5)) : null;
                if (ref != null) {
                    iv.referee = ref;
                    ROLES.put(ref, new Role(iv.village, iv.id));
                    iv.waitingFor = "referee";
                }
                iv.waitSince = gt;
                iv.nextAt = gt + 5;
                return;
            }
            case REF_GO -> {
                if (iv.referee != null) {
                    UUID ref = iv.referee;
                    iv.referee = null;
                    ROLES.remove(ref);
                    VillageFolkEntity rf = Civics.find(level, ref);
                    if (rf != null) leave(rf);
                }
                face(level, iv, "frown".equals(l.arg()) ? Act.FROWN : Act.NOD);
            }
            case BACK -> {
                if (c != null) c.seen = true;
                putBack(level, iv);
                if (cf != null && iv.letterWith != null && chair != null) {
                    ItemStack held = chair.getOffhandItem();
                    if (isLetter(held, iv.id)) {
                        chair.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                        cf.insertItem(held);
                    }
                    iv.letterWith = null;
                }
            }
            case NOD, FROWN, BROW -> face(level, iv, l.act());
            case SHAKE -> {
                iv.shaken = true;
                iv.waitingFor = "shake";
                iv.waitSince = gt;
                iv.nextAt = gt + 5;
                return;
            }
            default -> { }
        }
        if (l.act() == Act.CALL) {
            iv.waitSince = gt;
        }
        if (!l.text().isEmpty()) {
            if (who != null) {
                FolkTalk.speak(who, l.text());
                iv.lastSpeaker = who.getUUID();
                if (l.act() != Act.READ && l.act() != Act.SHOW && l.act() != Act.TASTE) who.swing(InteractionHand.MAIN_HAND);
            }
            String name = who != null ? who.displayNameCap() : l.who().equals("chair") ? iv.chairName : "?";
            iv.said.add(name + "|" + l.text());
            LOG.info("[MCA-INTERVIEW] {} | {}: {}", Villages.name(iv.village), name, l.text());
        }
        iv.nextAt = gt + line() + Math.min(40, l.text().length() / 4) * (hurry ? 0 : 1);
    }

    /** Who says a line: the panel's chair, master or councillor, the candidate across the table, or a folk by its id. */
    @Nullable
    private static VillageFolkEntity speaker(ServerLevel level, Interview iv, String who) {
        UUID id = switch (who) {
            case "chair" -> iv.chair;
            case "master" -> iv.master != null ? iv.master : iv.chair;
            case "council" -> iv.councillor != null ? iv.councillor : iv.chair;
            case "cand" -> iv.now() == null ? null : iv.now().id;
            default -> who.startsWith("folk:") || who.startsWith("cand:") ? parse(who.substring(5)) : null;
        };
        VillageFolkEntity f = id == null ? null : Civics.find(level, id);
        if (f == null && !who.startsWith("folk:") && !who.startsWith("cand")) f = iv.chair == null ? null : Civics.find(level, iv.chair);
        return f;
    }

    @Nullable
    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Is what a line waits on done (the candidate sat, the referee come, the chosen at the table), or waited long enough? */
    private static boolean ready(ServerLevel level, Interview iv, long gt) {
        long waited = gt - iv.waitSince;
        InterviewTable.Table t = iv.table;
        switch (iv.waitingFor) {
            case "seat" -> {
                Cand c = iv.now();
                VillageFolkEntity f = c == null ? null : Civics.find(level, c.id);
                boolean there = f != null && near(f, InterviewTable.spot(t, t.cand()), 1.2);
                if (there) iv.sat.add(f.getUUID());
                return f == null || there || waited > waitMost(600);
            }
            case "referee" -> {
                VillageFolkEntity f = iv.referee == null ? null : Civics.find(level, iv.referee);
                return f == null || near(f, Vec3.atBottomCenterOf(t.aside()), 2.0) || waited > waitMost(500);
            }
            case "shake" -> {
                VillageFolkEntity w = iv.winner == null ? null : Civics.find(level, iv.winner);
                boolean there = w == null || near(w, InterviewTable.spot(t, t.cand()), 1.4);
                if (there || waited > waitMost(400)) {
                    shake(level, iv);
                    return true;
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** The handshake across the table: both arms, the happy faces, a chime. */
    private static void shake(ServerLevel level, Interview iv) {
        VillageFolkEntity w = iv.winner == null ? null : Civics.find(level, iv.winner);
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        if (w != null) {
            w.swing(InteractionHand.MAIN_HAND);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, w.getX(), w.getY() + 2.0, w.getZ(), 14, 0.5, 0.4, 0.5, 0.05);
            if (chair != null) {
                w.getLookControl().setLookAt(chair, 30.0F, 30.0F);
                chair.getLookControl().setLookAt(w, 30.0F, 30.0F);
            }
            level.playSound(null, w.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.0F, 1.2F);
        }
        if (chair != null) chair.swing(InteractionHand.MAIN_HAND);
        if (iv.master != null) {
            VillageFolkEntity m = Civics.find(level, iv.master);
            if (m != null) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, m.getX(), m.getY() + 2.1, m.getZ(), 3, 0.2, 0.1, 0.2, 0.0);
        }
    }

    /** The panel's faces: a nod (green), a frown (a puff of smoke), a raised eyebrow (a note). */
    private static void face(ServerLevel level, Interview iv, Act act) {
        List<UUID> panel = new ArrayList<>();
        if (iv.master != null) panel.add(iv.master);
        if (iv.chair != null) panel.add(iv.chair);
        if (iv.councillor != null) panel.add(iv.councillor);
        if (panel.isEmpty()) return;
        VillageFolkEntity f = Civics.find(level, panel.get(level.getRandom().nextInt(panel.size())));
        if (f == null) return;
        switch (act) {
            case NOD -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 4, 0.25, 0.1, 0.25, 0.0);
            case FROWN -> level.sendParticles(ParticleTypes.SMOKE, f.getX(), f.getY() + 2.0, f.getZ(), 5, 0.12, 0.1, 0.12, 0.01);
            case BROW -> level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.3, f.getZ(), 0, 0.5, 0.0, 0.0, 1.0);
            default -> { }
        }
    }

    /** A piece of its work into its hand (from its pack, its chest, or the town's stores), to be put back after. */
    private static void show(ServerLevel level, Interview iv, VillageFolkEntity f) {
        InterviewScript.Piece piece = InterviewScript.piece(level, iv, f);
        if (piece == null) return;
        ItemStack thing;
        if (piece.chest != null && level.getBlockEntity(piece.chest) instanceof Container box) {
            thing = box.getItem(piece.slot);
            box.setItem(piece.slot, ItemStack.EMPTY);
            box.setChanged();
        } else if (piece.slot >= 0) {
            thing = f.getInventoryItems().get(piece.slot);
            f.getInventoryItems().set(piece.slot, ItemStack.EMPTY);
        } else {
            thing = ItemStack.EMPTY;                                              // in its hand already
        }
        if (!thing.isEmpty()) {
            iv.savedHand = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, thing);
        }
        iv.shown = piece;
        iv.shownBy = f.getUUID();
        f.swing(InteractionHand.MAIN_HAND);
        if (iv.master != null) {
            VillageFolkEntity m = Civics.find(level, iv.master);
            if (m != null) m.getLookControl().setLookAt(f.getX(), f.getEyeY() - 0.3, f.getZ());
        }
    }

    /** The piece held up put back where it came from, and what was in its hand back in it. */
    private static void putBack(ServerLevel level, Interview iv) {
        if (iv.shown == null || iv.shownBy == null) return;
        InterviewScript.Piece piece = iv.shown;
        VillageFolkEntity f = Civics.find(level, iv.shownBy);
        iv.shown = null;
        iv.shownBy = null;
        if (f == null || piece.slot == -2) {
            iv.savedHand = ItemStack.EMPTY;
            return;
        }
        ItemStack thing = f.getMainHandItem();
        f.setItemSlot(EquipmentSlot.MAINHAND, iv.savedHand);
        iv.savedHand = ItemStack.EMPTY;
        if (thing.isEmpty()) return;
        if (piece.chest != null && level.getBlockEntity(piece.chest) instanceof Container box && box.getItem(piece.slot).isEmpty()) {
            box.setItem(piece.slot, thing);
            box.setChanged();
            return;
        }
        ItemStack left = f.insertItem(thing);
        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
    }

    /** Two waiting their turn whisper, now and then. */
    private static void chatter(ServerLevel level, Interview iv, long gt) {
        if (iv.stage != Stage.SITTING || gt < iv.chatterAt) return;
        iv.chatterAt = gt + (hurry ? 120 : 500 + level.getRandom().nextInt(300));
        List<VillageFolkEntity> waiting = new ArrayList<>();
        for (int i = 0; i < iv.cands.size(); i++) {
            Cand c = iv.cands.get(i);
            if (c.absent || i == iv.current) continue;
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null && near(f, InterviewTable.spot(iv.table, bench(iv, c)), 2.0)) waiting.add(f);
        }
        if (waiting.size() < 2) {
            if (waiting.size() == 1) waiting.get(0).swing(InteractionHand.OFF_HAND);       // a fidget with its letter
            return;
        }
        VillageFolkEntity a = waiting.get(level.getRandom().nextInt(waiting.size())), b = waiting.get((waiting.indexOf(a) + 1) % waiting.size());
        String[] said = InterviewScript.chatter(a, b, level.getRandom());
        FolkTalk.speak(a, said[0]);
        b.sayLater(said[1], 40);
        a.swing(InteractionHand.OFF_HAND);
        a.getLookControl().setLookAt(b, 30.0F, 30.0F);
        iv.said.add(a.displayNameCap() + "|" + said[0]);
        iv.said.add(b.displayNameCap() + "|" + said[1]);
        LOG.info("[MCA-INTERVIEW] {} | {} (on the bench): {} — {}: {}", Villages.name(iv.village), a.displayNameCap(), said[0], b.displayNameCap(), said[1]);
    }

    /** A player standing by the table is greeted by the chair (once an interview). */
    private static void greet(ServerLevel level, Interview iv) {
        if (iv.table == null || iv.chair == null) return;
        Vec3 at = Vec3.atCenterOf(iv.table.cand().pos());
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(at) > 7.0 * 7.0 || !iv.greeted.add(p.getUUID())) continue;
            VillageFolkEntity chair = Civics.find(level, iv.chair);
            if (chair == null) return;
            String name = p.getName().getString();
            String hello = p.getUUID().equals(iv.guest) ? "Ah, " + name + " — your chair's at the end. We've begun."
                : PlayerLeader.leads(iv.village, p.getUUID()) ? "Ah — " + name + ". Your panel, your choice: we'll put the questions."
                : FolkTalk.pick(level.getRandom(), "Ah, " + name + " — come to watch? Stand by the wall; we're interviewing for " + iv.title + ".",
                    "Good morning, " + name + ". Interviews — " + iv.title + ". You're welcome to listen.");
            FolkTalk.speak(chair, hello);
            iv.said.add(chair.displayNameCap() + "|" + hello);
            return;
        }
    }

    // ------------------------------------------------------------------ the folk's part

    /** Sat in its chair or on the bench at an interview (the park leaves it sat). */
    public static boolean seated(VillageFolkEntity f) {
        return f.getPose() == Pose.SITTING && ROLES.containsKey(f.getUUID());
    }

    /** Is it held by an interview just now (at it, or on the road to or from one)? Its own day waits (VillageFolkEntity.calledAway). */
    public static boolean busy(VillageFolkEntity f) {
        return VISITS.containsKey(f.getUUID()) || ROLES.containsKey(f.getUUID());
    }

    /**
     * Every fourth tick (VillageFolkEntity.aiStep): its place at the interview and what it is doing there — the panel
     * in its chairs, a candidate on the bench with its letter or across the table, a referee come to speak, the
     * candidates out to wait while the panel huddles, all back for the choice; or on the road to or from it. True while
     * it holds the folk.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID me = f.getUUID();
        Visit vis = VISITS.get(me);
        if (vis != null) return travel(f, level, vis);
        Role role = ROLES.get(me);
        if (role == null) return false;
        Interview iv = find(role.village(), role.id());
        if (iv == null || !iv.stage.open() || iv.stage == Stage.AWAITING) {
            ROLES.remove(me);
            leave(f);
            return false;
        }
        if (f.isSleeping()) f.stopSleeping();
        if (Seats.seatForTests(f) != null) Seats.stand(f, null);                 // off a bench of its own choosing: it has a chair here
        Cand c = iv.cand(me);
        InterviewTable.Table t = iv.table;
        if (iv.stage == Stage.SET || t == null) {
            // From away, and here a day early: it waits about the board (the inn takes it in at night).
            Villages.Village v = Villages.get(iv.village);
            if (v == null) return false;
            goTo(f, Vec3.atBottomCenterOf(meet(level, v, iv)), false, null, 2.5);
            f.hobbyNow = "waiting in " + Villages.name(iv.village) + " for its interview";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        Vec3 spot;
        boolean sit;
        net.minecraft.core.Direction look;
        String doing;
        boolean panel = me.equals(iv.chair) || me.equals(iv.master) || me.equals(iv.councillor);
        if (panel) {
            int seat = me.equals(iv.chair) ? t.panel().size() / 2 : me.equals(iv.master) ? 0 : t.panel().size() - 1;
            InterviewTable.Seat s = t.panel().get(Math.min(seat, t.panel().size() - 1));
            if (iv.stage == Stage.CONFERRING && iv.step > 1) {
                int k = me.equals(iv.chair) ? 0 : me.equals(iv.master) ? 1 : 2;
                spot = Vec3.atBottomCenterOf(t.huddle()).add((k - 1) * 0.7, 0, (k == 0 ? 0.5 : -0.3));
                sit = false;
                look = null;
                doing = "conferring with the panel, quietly";
            } else {
                spot = InterviewTable.spot(t, s);
                sit = t.seated() && !(iv.stage == Stage.ANNOUNCING && me.equals(iv.chair) && iv.shaken);
                look = s.look();
                doing = (me.equals(iv.chair) ? "chairing" : "on the panel for") + " the interviews for " + iv.title;
            }
            if (me.equals(iv.chair) && iv.letterWith != null) {
                // The chair holds the letter while it reads.
                doing = "reading a letter of application";
            }
        } else if (c != null) {
            int idx = iv.cands.indexOf(c);
            if (iv.stage == Stage.CONFERRING) {
                spot = Vec3.atBottomCenterOf(t.out()).add((idx % 3 - 1) * 1.2, 0, (idx / 3) * 1.2);
                sit = false;
                look = null;
                doing = "waiting while the panel confers";
            } else if (iv.stage == Stage.ANNOUNCING) {
                if (iv.shaken && me.equals(iv.winner)) {
                    spot = InterviewTable.spot(t, t.cand());
                    sit = false;
                    look = t.cand().look();
                    doing = "shaking hands on the post of " + iv.title;
                } else {
                    InterviewTable.Seat s = t.front().get(Math.min(idx, t.front().size() - 1));
                    spot = Vec3.atBottomCenterOf(s.pos()).add(idx >= t.front().size() ? (idx - t.front().size() + 1) * 0.8 : 0, 0, 0);
                    sit = false;
                    look = s.look();
                    doing = "hearing the panel's choice";
                }
            } else if (idx == iv.current && iv.step > 0 && !c.seen) {
                spot = InterviewTable.spot(t, t.cand());
                sit = t.seated();
                look = t.cand().look();
                doing = "across the table from the panel, interviewed for " + iv.title;
            } else {
                InterviewTable.Seat s = bench(iv, c);
                spot = InterviewTable.spot(t, s);
                sit = t.seated() && t.bench().size() > idx;
                look = s.look();
                doing = c.seen ? "waiting on the bench, its interview done" : "waiting its turn on the bench, letter in hand";
                if (!c.seen) holdLetter(f, iv.id);
            }
        } else if (me.equals(iv.referee)) {
            spot = Vec3.atBottomCenterOf(t.aside());
            sit = false;
            look = t.cand().look();
            doing = "speaking at an interview for " + (iv.now() == null ? "a friend" : iv.now().name);
        } else {
            ROLES.remove(me);
            return false;
        }
        goTo(f, spot, sit, look, sit ? 1.0 : 1.4);
        // Eyes on whoever spoke last (the speaker on the one it speaks to).
        VillageFolkEntity sp = iv.lastSpeaker == null || iv.lastSpeaker.equals(me) ? null : Civics.find(level, iv.lastSpeaker);
        if (sp != null && sp.distanceToSqr(f) < 16 * 16) f.getLookControl().setLookAt(sp, 30.0F, 30.0F);
        else if (iv.lastSpeaker != null && iv.lastSpeaker.equals(me) && iv.now() != null && !me.equals(iv.now().id)) {
            VillageFolkEntity across = Civics.find(level, iv.now().id);
            if (across != null) f.getLookControl().setLookAt(across, 30.0F, 30.0F);
        }
        if (c != null && !c.seen && iv.stage == Stage.SITTING && iv.cands.indexOf(c) != iv.current && f.tickCount % 120 == 4 && level.getRandom().nextInt(3) == 0) {
            f.swing(InteractionHand.OFF_HAND);                                          // fidgeting with its letter
        }
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** The bench seat for this candidate (by its place in the list). */
    private static InterviewTable.Seat bench(Interview iv, Cand c) {
        List<InterviewTable.Seat> b = iv.table.bench();
        int i = Math.max(0, iv.cands.indexOf(c));
        if (i < b.size()) return b.get(i);
        InterviewTable.Seat last = b.get(b.size() - 1);
        return new InterviewTable.Seat(last.pos().relative(last.look().getClockWise(), i - b.size() + 1), last.look());
    }

    private static boolean near(VillageFolkEntity f, Vec3 spot, double reach) {
        double dx = f.getX() - spot.x, dz = f.getZ() - spot.z;
        return dx * dx + dz * dz <= reach * reach && Math.abs(f.getY() - spot.y) < 1.6;
    }

    /**
     * To its place: walked to (the path renewed now and then), set down there if it makes no headway (a door, a crowd),
     * then sat (on the stair, facing its way) or stood.
     */
    private static void goTo(VillageFolkEntity f, Vec3 spot, boolean sit, @Nullable net.minecraft.core.Direction look, double reach) {
        double dx = f.getX() - spot.x, dz = f.getZ() - spot.z, d = dx * dx + dz * dz;
        double[] w = WALKS.computeIfAbsent(f.getUUID(), k -> new double[]{ Double.MAX_VALUE, f.tickCount, -1000 });
        boolean there = d <= reach * reach && Math.abs(f.getY() - spot.y) < 1.6;
        if (!there) {
            if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            if (d < w[0] - 0.5) { w[0] = d; w[1] = f.tickCount; }
            if (f.getNavigation().isDone() || f.tickCount - w[2] > 40) {
                f.walkTo(BlockPos.containing(spot), 0.85D);
                w[2] = f.tickCount;
            }
            if (f.tickCount - w[1] > (hurry ? 80 : 300)) {
                f.getNavigation().stop();
                f.moveTo(spot.x, spot.y, spot.z, f.getYRot(), 0.0F);
                w[0] = 0;
                w[1] = f.tickCount;
            }
            return;
        }
        w[0] = Double.MAX_VALUE;
        w[1] = f.tickCount;
        f.getNavigation().stop();
        if (sit) {
            float yaw = look == null ? f.getYRot() : look.toYRot();
            if (f.getPose() != Pose.SITTING || d > 0.15) {
                f.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
                f.setPose(Pose.SITTING);
            }
            f.setYBodyRot(yaw);
        } else {
            if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            if (look != null) f.setYBodyRot(look.toYRot());
        }
    }

    /** Let go: up off its chair, its letter put away. */
    private static void leave(VillageFolkEntity f) {
        if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        pocketLetter(f);
        WALKS.remove(f.getUUID());
        f.getNavigation().stop();
    }

    /** Everybody the interview held, let go: the panel and the candidates to their day, those from away off home. */
    static void release(ServerLevel level, Interview iv) {
        putBack(level, iv);
        for (Map.Entry<UUID, Role> e : new ArrayList<>(ROLES.entrySet())) {
            if (!e.getValue().village().equals(iv.village) || e.getValue().id() != iv.id) continue;
            ROLES.remove(e.getKey());
            VillageFolkEntity f = Civics.find(level, e.getKey());
            if (f == null) continue;
            // A letter still in the chair's hand goes back to whose it is.
            if (isLetter(f.getOffhandItem(), iv.id) && iv.cand(f.getUUID()) == null) {
                ItemStack letter = f.getOffhandItem();
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                String writer = LetterOfApplicationItem.words(letter).getString("Name");
                VillageFolkEntity to = null;
                for (Cand c : iv.cands) if (c.name.equals(writer)) to = Civics.find(level, c.id);
                ItemStack left = (to != null ? to : f).insertItem(letter);
                if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
            }
            leave(f);
            Cand c = iv.cand(f.getUUID());
            if (c != null && c.outside && (iv.stage == Stage.DONE || iv.stage == Stage.CANCELLED)) goHome(level, f, iv);
        }
        iv.letterWith = null;
        iv.referee = null;
    }

    /** What it is about for the top of its card ("Waiting its turn on the bench…"), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        if (!busy(f)) return null;
        String h = f.hobbyNow();
        return h == null || h.isEmpty() ? "At an interview" : InterviewScript.capital(h);
    }

    // ------------------------------------------------------------------ cards, spirits, a week's hard work

    /** A line on a folk's card about its interviews ("day|line"), the latest three kept. */
    static void card(UUID folk, long day, String line) {
        List<String> lines = InterviewBook.of().cards.computeIfAbsent(folk, k -> new ArrayList<>());
        lines.removeIf(l -> l.contains("|Shortlisted") && line.startsWith("Interviewed"));
        lines.add(day + "|" + line);
        while (lines.size() > 3) lines.remove(0);
        InterviewBook.changed();
    }

    /** Its card's line: its latest interview, and how it went. */
    public static String cardLine(VillageFolkEntity f) {
        List<String> lines = InterviewBook.of().cards.get(f.getUUID());
        if (lines == null || lines.isEmpty()) return "";
        String l = lines.get(lines.size() - 1);
        int bar = l.indexOf('|');
        return bar < 0 ? l : l.substring(bar + 1);
    }

    /** Every line on its card, oldest first (tests, the command). */
    public static List<String> cardLines(UUID folk) {
        List<String> out = new ArrayList<>();
        for (String l : InterviewBook.of().cards.getOrDefault(folk, List.of())) out.add(l.substring(l.indexOf('|') + 1));
        return out;
    }

    /** Its spirits (VillageFolkEntity.refreshMood): proud of a post won at interview, a little down at one missed. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        long[] a = InterviewBook.of().after.get(f.getUUID());
        if (a == null || a.length < 2) return m;
        if (a[1] == 1 && day - a[0] <= 3) {
            why.add(new Object[]{ "interviewed", 7 });
            return m + 7;
        }
        if (a[1] == 0 && day - a[0] <= 2) {
            why.add(new Object[]{ "passedover", 5 });
            return m - 5;
        }
        return m;
    }

    /** How it puts it, asked how it is. */
    public static String moodWords(VillageFolkEntity f, String why) {
        return why.equals("interviewed") ? FolkTalk.pick(f.getRandom(), "I got the post! At interview, too.", "I'm the new one — chosen at interview!")
            : FolkTalk.pick(f.getRandom(), "I didn't get the post I was interviewed for.", "Turned down at interview. I'll get it next time.");
    }

    /** Turned down at interview: it works the harder for a week, and learns its trade a fifth quicker (VillageFolkEntity.creditTrade). */
    public static int extraXp(VillageFolkEntity f, int amount) {
        if (amount <= 0) return 0;
        long[] a = InterviewBook.of().after.get(f.getUUID());
        if (a == null || a.length < 2 || a[1] != 0) return 0;
        long day = f.level().getDayTime() / 24000L;
        if (day - a[0] > 7) return 0;
        return amount / 5 + (f.getRandom().nextInt(5) < amount % 5 ? 1 : 0);
    }

    // ------------------------------------------------------------------ the day's look over the posts

    /**
     * Once a day for each town: the posts it reckons from who is best (the constable, the cave team's leader, the
     * auctioneer, a trade's master), wanting a holder the panel chose and two who want it, set for interview; and a new
     * workplace's notice the town's own want, though nobody else has applied.
     */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.folkOf(id).size() < 4 || coming(id).size() >= 2) return;
        InterviewBook.Town town = InterviewBook.town(id);
        List<Post> posts = new ArrayList<>(List.of(Post.of("constable"), Post.of("caveleader"), Post.of("auctioneer")));
        for (StationTask t : StationTask.values()) if (t != StationTask.NONE && Lessons.of(t) != null) posts.add(Post.master(t));
        for (Post p : posts) {
            if (p == null || !InterviewPosts.stands(level, v, p) || pending(id, p.key()) != null) continue;
            Long quiet = town.quiet.get(p.key());
            if (quiet != null && day - quiet < 3) continue;
            boolean recent = false;
            for (Interview iv : held(id)) if (iv.post.equals(p.key()) && day - iv.decidedDay < 7) recent = true;
            if (recent) continue;
            VillageFolkEntity kept = holder(id, p.key());
            if (kept != null && InterviewPosts.eligible(p, kept, id)) continue;          // the panel's choice still holds it
            List<VillageFolkEntity> cands = InterviewPosts.candidates(level, v, p, null, false, Set.of());
            if (cands.size() < 2) {
                town.quiet.put(p.key(), day);
                continue;
            }
            schedule(level, v, p, cands, List.of(), day, false);
            return;                                                                          // one new interview a day
        }
        for (JobMarket.Opening o : JobMarket.open(id)) {
            if (!workplace(o) || pending(id, "opening:" + o.id) != null) continue;
            boolean applied = false;
            for (JobMarket.Application a : JobMarket.applications(id)) if (a.opening == o.id && a.verdict == JobMarket.Verdict.WAITING) applied = true;
            if (applied) continue;                                                   // the leader's look hands it here (shortlist)
            Post p = Post.opening(o);
            List<VillageFolkEntity> own = InterviewPosts.candidates(level, v, p, null, false, Set.of());
            if (own.size() < 2) continue;
            schedule(level, v, p, own, List.of(), day, false);
            return;
        }
        InterviewBook.changed();
    }

    // ------------------------------------------------------------------ players

    /**
     * A player at the talk screen: asking about the interviews ("any interviews coming up?"), putting in a good word for a
     * candidate ("I'd recommend Ada for the post"), asking to sit on the panel, or for the interview page. Null if it was
     * none of these.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        UUID id = f.ownerId();
        if (id == null || text == null || text.isEmpty() || !(p instanceof ServerPlayer sp)) return null;
        String low = text.toLowerCase(Locale.ROOT);
        boolean word = low.contains("recommend") || low.contains("good word") || low.contains("speak for") || low.contains("vouch for")
            || low.contains("put in a word");
        if (word) return recommend((ServerLevel) f.level(), sp, id, text, f);
        if (low.contains("interview") && (low.contains("page") || low.contains("choose"))) {
            page(sp);
            return "Here — the interview, and the candidates.";
        }
        if (low.contains("panel") && (low.contains("sit") || low.contains("join"))) return panel((ServerLevel) f.level(), sp, id);
        if (!low.contains("interview")) return null;
        List<Interview> coming = coming(id);
        if (coming.isEmpty()) {
            Interview last = held(id).isEmpty() ? null : held(id).get(0);
            return last == null ? "No interviews coming up. When a post falls vacant, they'll go on the board."
                : "Nothing coming. The last was for " + last.title + ": " + last.winnerName + " got it — " + last.reason + ".";
        }
        Interview iv = coming.get(0);
        Cand mine = iv.cand(f.getUUID());
        long day = f.level().getDayTime() / 24000L;
        String when = iv.stage.on() ? "going on now" : iv.dueDay == day ? "this morning" : iv.dueDay == day + 1 ? "tomorrow morning" : "on day " + (iv.dueDay + 1);
        String where = iv.where.isEmpty() ? (InterviewTable.known(id) != null && InterviewTable.known(id).hall() ? "the hall" : "the board") : iv.where;
        String s = "Interviews " + when + " at " + where + " for the post of " + iv.title + ". " + capital(JobMarket.words(iv.cands.size()))
            + " stand: " + JobMarket.join(names(iv)) + ". " + iv.chairName + " chairs.";
        if (mine != null) s += " I'm one of them! Wish me luck.";
        else if (Standing.of(id, p.getUUID(), f.level().getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            s += " If you know one of them, you could put in a good word: say \"I'd recommend " + iv.cands.get(0).name + " for the post\".";
        }
        if (PlayerLeader.leads(id, p.getUUID())) s += " It's your choice in the end: \"show me the interview page\".";
        return s;
    }

    /** "I'd recommend Ada for the post": a reference, weighted by what the town thinks of the player. */
    static String recommend(ServerLevel level, ServerPlayer p, UUID village, String text, @Nullable VillageFolkEntity to) {
        Interview iv = null;
        Cand who = null;
        String low = text.toLowerCase(Locale.ROOT);
        for (Interview x : coming(village)) {
            for (Cand c : x.cands) {
                if (low.contains(c.name.toLowerCase(Locale.ROOT)) && (iv == null || who.name.length() < c.name.length())) { iv = x; who = c; }
            }
        }
        if (iv == null) return coming(village).isEmpty() ? "There's no interview coming up to put in a word for anybody."
            : "Who? The candidates are " + JobMarket.join(names(coming(village).get(0))) + ".";
        if (who.seen || iv.stage == Stage.CONFERRING || iv.stage == Stage.ANNOUNCING) return "Too late for " + who.name + ": it's had its turn.";
        for (Cand c : iv.cands) for (Ref r : c.words) if (r.player && r.by.equals(p.getUUID())) {
            return "You've put in your word already, for " + c.name + ". One word each.";
        }
        Standing.View view = Standing.of(village, p.getUUID(), level.getGameTime());
        if (!view.title().atLeast(Standing.Title.FRIEND)) {
            return "I'll be honest: a word from you wouldn't help " + who.name + " here. The town doesn't know you well enough.";
        }
        int weight = view.title() == Standing.Title.HERO ? 7 : view.title() == Standing.Title.HONOURED ? 5 : 3;
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        if (chair != null && chair.persona().affinity(p.getUUID()) < Social.FRIEND) weight = Math.max(1, weight * 2 / 3);
        Ref r = new Ref();
        r.by = p.getUUID();
        r.byName = p.getName().getString() + ", " + view.title().words;
        r.player = true;
        r.kind = "player";
        r.weight = weight;
        r.line = "\"" + clip(text.trim(), 80) + "\"";
        who.words.add(r);
        InterviewBook.changed();
        Villages.Village v = Villages.get(village);
        LOG.info("[MCA-INTERVIEW] {}: {} puts in a word for {} (weight {})", v == null ? "?" : Villages.name(village), p.getName().getString(), who.name, weight);
        return FolkTalk.pick(level.getRandom(), "I'll see " + iv.chairName + " hears it. A word from you counts for something here.",
            "Kind of you. " + who.name + " will be glad of it — I'll pass it on.");
    }

    /** The command's word put in for a candidate. */
    public static String recommendWord(ServerLevel level, ServerPlayer p, UUID village, String text) {
        return recommend(level, p, village, text, null);
    }

    /** The command's ask for a seat on the panel. */
    public static String panelSeat(ServerLevel level, ServerPlayer p, UUID village) {
        return panel(level, p, village);
    }

    /** The posts the stage knows, for the command's suggestions. */
    public static List<String> postKeys() {
        return InterviewPosts.keys();
    }

    /** An honoured guest (or the town's hero) asks to sit on the panel: a chair at the end, and a vote of three. */
    static String panel(ServerLevel level, ServerPlayer p, UUID village) {
        List<Interview> coming = coming(village);
        if (coming.isEmpty()) return "There's no interview to sit on just now.";
        Interview iv = coming.get(0);
        if (iv.stage == Stage.CONFERRING || iv.stage == Stage.ANNOUNCING) return "The panel's deciding already.";
        if (PlayerLeader.leads(village, p.getUUID())) return "You chair it already: it's your choice.";
        Standing.View view = Standing.of(village, p.getUUID(), level.getGameTime());
        if (!view.title().atLeast(Standing.Title.HONOURED)) return "The panel's for the town's own, and its honoured guests.";
        if (iv.guest != null && !iv.guest.equals(p.getUUID())) return iv.guestName + " sits with the panel already.";
        iv.guest = p.getUUID();
        iv.guestName = p.getName().getString();
        InterviewBook.changed();
        return "We'd be honoured. Your chair's at the end; your choice counts as one vote of three. (/village interviews page)";
    }

    /**
     * A player chooses (the leader's choice stands; a guest's is one vote), or leaves it to the panel ("panel"). Says what
     * came of it.
     */
    public static String choose(ServerLevel level, ServerPlayer p, String name) {
        UUID village = PlayerLeader.townLedBy(p.getUUID());
        Interview iv = null;
        if (village != null) {
            Interview on = running(village);
            iv = on != null ? on : coming(village).isEmpty() ? null : coming(village).get(0);
        }
        if (iv == null) {
            Villages.Village near = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
            if (near != null) {
                Interview on = running(near.id());
                Interview x = on != null ? on : coming(near.id()).isEmpty() ? null : coming(near.id()).get(0);
                if (x != null && p.getUUID().equals(x.guest)) iv = x;
            }
        }
        if (iv == null) return "There's no interview of yours to choose in.";
        if (iv.stage == Stage.ANNOUNCING || iv.stage == Stage.DONE) return "It's been decided: " + iv.winnerName + ".";
        if (name.equalsIgnoreCase("panel") || name.equalsIgnoreCase("steward")) {
            iv.chose.remove(p.getUUID());
            if (PlayerLeader.leads(iv.village, p.getUUID())) iv.playerChairs = false;
            InterviewBook.changed();
            return "The panel will decide the post of " + iv.title + ".";
        }
        Cand pick = null;
        for (Cand c : iv.cands) if (c.name.equalsIgnoreCase(name.trim())) pick = c;
        if (pick == null) return "No candidate by that name: " + JobMarket.join(names(iv)) + ".";
        iv.chose.put(p.getUUID(), pick.id);
        if (PlayerLeader.leads(iv.village, p.getUUID())) iv.playerChairs = true;
        InterviewBook.changed();
        LOG.info("[MCA-INTERVIEW] {}: {} chooses {} for {}", Villages.name(iv.village), p.getName().getString(), pick.name, iv.title);
        if (iv.stage == Stage.AWAITING) {
            Villages.Village v = Villages.get(iv.village);
            if (v != null) finish(level, v, iv, choose(level, iv), false);
        }
        return PlayerLeader.leads(iv.village, p.getUUID()) ? "Your choice: " + pick.name + ". The chair will tell them."
            : "Your vote: " + pick.name + ". It counts as one of three.";
    }

    /**
     * The interview page (the civic page's screen): the post, the day, the panel, and each candidate's particulars —
     * its level and years, its record, its nature, the words put in for it, and how its interview has gone — with a
     * Choose for each for the player who leads or sits on the panel, a word to put in for the others.
     */
    public static void page(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        UUID village = PlayerLeader.townLedBy(p.getUUID());
        if (village == null) {
            Villages.Village near = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
            village = near == null ? null : near.id();
        }
        if (village == null) {
            PlayerCivic.send(p, "Interviews", "No town near.", List.of());
            return;
        }
        List<Interview> coming = coming(village);
        Interview iv = running(village) != null ? running(village) : coming.isEmpty() ? null : coming.get(0);
        StringBuilder sb = new StringBuilder();
        List<String> buttons = new ArrayList<>();
        if (iv == null) {
            sb.append("No interview coming up in ").append(Villages.name(village)).append(".");
            List<Interview> held = held(village);
            if (!held.isEmpty()) {
                Interview last = held.get(0);
                sb.append("\nThe last: ").append(last.title).append(", day ").append(last.decidedDay + 1).append(": ").append(last.winnerName)
                    .append(" — ").append(last.reason).append(".");
            }
            PlayerCivic.send(p, "Interviews: " + Villages.name(village), sb.toString(), buttons);
            return;
        }
        boolean leads = PlayerLeader.leads(village, p.getUUID()), guest = p.getUUID().equals(iv.guest);
        long day = level.getDayTime() / 24000L;
        sb.append("Post: ").append(iv.title).append(iv.opening >= 0 ? " (the notice on the board)" : "").append(".");
        sb.append("\nWhen: ").append(iv.stage.on() ? "now, at " + iv.where : iv.stage == Stage.AWAITING ? "heard; the choice waits on you till sundown"
            : (iv.dueDay == day ? "this morning" : "day " + (iv.dueDay + 1)) + ".");
        sb.append("\nPanel: ").append(iv.chairName).append(iv.playerChairs && iv.chair != null ? " (its steward puts the questions)" : "")
            .append(iv.masterName.isEmpty() ? "" : ", " + iv.masterName).append(iv.councillorName.isEmpty() ? "" : ", " + iv.councillorName)
            .append(iv.guest == null ? "" : ", and " + iv.guestName + " (a guest)").append(".");
        UUID mine = iv.chose.get(p.getUUID());
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            sb.append("\n\n").append(c.name).append(c.outside ? " of " + c.homeName : "").append(": ");
            StationTask t = JobMarket.named(c.trade);
            sb.append(t == null || t == StationTask.NONE ? "no trade" : t.title).append(", level ").append(c.level).append("; ").append(c.age)
                .append(" years");
            if (c.knacks > 0) sb.append("; ").append(c.knacks).append(c.knacks == 1 ? " knack" : " knacks");
            if (f != null) {
                sb.append("; ").append(f.life().traitsLabel().toLowerCase(Locale.ROOT));
                int conv = Crime.convictions(f.getUUID());
                if (conv > 0) sb.append("; before the court ").append(JobMarket.words(conv)).append(conv == 1 ? " time" : " times");
            }
            sb.append(".\n- On paper: ").append(c.paper).append(c.good.isEmpty() ? "" : " (" + c.good + ")");
            if (!c.words.isEmpty()) {
                List<String> refs = new ArrayList<>();
                for (Ref r : c.words) refs.add(r.byName + (r.player ? "" : " (" + r.kind + ")"));
                sb.append("\n- Words for it: ").append(String.join(", ", refs));
            }
            if (c.seen) {
                sb.append("\n- At interview: ").append(parts(c)).append(" → ").append(c.paper + c.interview());
            } else if (c.absent) {
                sb.append("\n- Not here: its letter read out.");
            } else {
                sb.append("\n- Not seen yet").append(c.letter ? "; its letter written." : "; no letter.");
            }
            if (c.id.equals(mine)) sb.append("\n- Your choice.");
            if (leads || guest) buttons.add("Choose " + c.name + "\tvillage interviews choose " + c.name + "\t" + (leads ? "Your choice stands" : "One vote of three"));
            else if (!c.seen && Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
                buttons.add("Recommend " + c.name + "\tvillage interviews recommend " + c.name + "\tPut in a good word for " + c.name);
            }
        }
        if (leads) buttons.add("Panel decides\tvillage interviews choose panel\tLeave the choice to the panel");
        if (!leads && !guest && Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.HONOURED)) {
            buttons.add("Sit on the panel\tvillage interviews panel\tA chair at the end, and a vote of three");
        }
        buttons.add("Refresh\tvillage interviews page\tThe page brought up to date");
        PlayerCivic.send(p, "Interview: " + iv.title + ", " + Villages.name(village), sb.toString(), buttons);
    }

    /** "nerves -2, letter +3, answers +4, work +4, words +3" */
    static String parts(Cand c) {
        List<String> out = new ArrayList<>();
        add(out, "nerves", c.nerves);
        add(out, "letter", c.prep);
        add(out, "answers", c.answers);
        add(out, "evidence", c.evidence);
        add(out, "references", c.refs);
        add(out, "honesty", c.honesty);
        add(out, "boast", c.boast);
        add(out, "absent", c.away);
        return out.isEmpty() ? "nothing either way" : String.join(", ", out);
    }

    private static void add(List<String> out, String what, int n) {
        if (n != 0) out.add(what + " " + (n > 0 ? "+" : "") + n);
    }

    // ------------------------------------------------------------------ the town's books

    /** The board's lines: the interview coming or on, and the last held. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        Interview on = running(village);
        if (on != null) {
            out.add("LG|Interviewing now at " + on.where + ": the post of " + on.title + " — come and watch.");
        } else {
            List<Interview> coming = coming(village);
            if (!coming.isEmpty()) {
                Interview iv = coming.get(0);
                InterviewTable.Table t = InterviewTable.known(village);
                String where = t != null && t.hall() ? "the hall" : "the board";
                String when = iv.dueDay <= day ? "this morning" : iv.dueDay == day + 1 ? "tomorrow morning" : "day " + (iv.dueDay + 1);
                out.add("LG|Interviews at " + where + " " + when + ": the post of " + iv.title + ". " + capital(JobMarket.words(iv.cands.size()))
                    + (iv.cands.size() == 1 ? " candidate: " : " candidates: ") + JobMarket.join(names(iv)) + ".");
            }
        }
        List<Interview> held = held(village);
        if (!held.isEmpty() && day - held.get(0).decidedDay <= 2) {
            Interview last = held.get(0);
            out.add("LN|Interviewed day " + (last.decidedDay + 1) + ": " + last.winnerName + " got the post of " + last.title + ".");
        }
        return out;
    }

    /** The crier's lines: tomorrow's interviews, and yesterday's choice. */
    public static List<String> crierLines(ServerLevel level, Villages.Village v, long day) {
        List<String> out = new ArrayList<>();
        for (Interview iv : coming(v.id())) {
            if (iv.dueDay != day + 1 && iv.dueDay != day) continue;
            out.add("Interviews " + (iv.dueDay == day ? "this very morning" : "tomorrow morning") + " for the post of " + iv.title + ": "
                + JobMarket.join(names(iv)) + " stand!");
            break;
        }
        for (Interview iv : held(v.id())) {
            if (iv.decidedDay < day - 1) break;
            out.add(iv.winnerName + " was chosen as our " + iv.title + (iv.cands.size() > 1 ? ", after " + (iv.close ? "a close" : "an")
                + " interview with " + JobMarket.join(others(iv)) : "") + "!");
            break;
        }
        return out;
    }

    private static List<String> others(Interview iv) {
        List<String> out = new ArrayList<>();
        for (Cand c : iv.cands) if (!c.id.equals(iv.winner)) out.add(c.name);
        return out;
    }

    /** The gazette's piece: yesterday's interviews and their choice, and those set. */
    @Nullable
    public static String gazette(UUID village, long day) {
        List<String> lines = new ArrayList<>();
        for (Interview iv : held(village)) {
            if (iv.decidedDay != day - 1) continue;
            lines.add(iv.winnerName + " got the post of " + iv.title + (iv.cands.size() > 1 ? " after " + (iv.close ? "a close" : "an")
                + " interview with " + JobMarket.join(others(iv)) : "") + ": " + iv.reason + ".");
        }
        for (Interview iv : coming(village)) {
            lines.add("Interviews day " + (iv.dueDay + 1) + " for " + iv.title + ": " + JobMarket.join(names(iv)) + ".");
        }
        if (lines.isEmpty()) return null;
        return "§lInterviews§r\n" + String.join("\n", lines.subList(0, Math.min(4, lines.size())));
    }

    /** The city books' Interviews page (client/InterviewsPage): the coming and the held, each candidate's score part by part. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        ListTag list = new ListTag();
        List<Interview> all = new ArrayList<>(coming(village));
        all.addAll(held(village));
        for (Interview iv : all) {
            if (list.size() >= 8) break;
            CompoundTag t = new CompoundTag();
            t.putInt("id", iv.id);
            t.putString("title", iv.title);
            t.putString("stage", iv.stage.name().toLowerCase(Locale.ROOT));
            t.putLong("due", iv.dueDay);
            t.putLong("decided", iv.decidedDay);
            t.putString("where", iv.where);
            t.putString("panel", iv.chairName + (iv.masterName.isEmpty() ? "" : ", " + iv.masterName)
                + (iv.councillorName.isEmpty() ? "" : ", " + iv.councillorName) + (iv.guest == null ? "" : ", " + iv.guestName));
            t.putString("winner", iv.winnerName);
            t.putString("reason", iv.reason);
            t.putBoolean("close", iv.close);
            ListTag cs = new ListTag();
            for (Cand c : iv.cands) {
                CompoundTag x = new CompoundTag();
                x.putString("name", c.name);
                x.putString("from", c.outside ? c.homeName : "");
                StationTask tr = JobMarket.named(c.trade);
                x.putString("trade", tr == null || tr == StationTask.NONE ? "" : tr.title);
                x.putInt("level", c.level);
                x.putInt("age", c.age);
                x.putInt("paper", c.paper);
                x.putIntArray("parts", new int[]{ c.nerves, c.prep, c.answers, c.evidence, c.refs, c.honesty, c.boast, c.away });
                x.putInt("total", c.seen || iv.stage == Stage.DONE ? c.paper + c.interview() : c.paper);
                x.putBoolean("seen", c.seen);
                x.putBoolean("absent", c.absent);
                x.putBoolean("letter", c.letter);
                x.putString("outcome", c.outcome);
                x.putString("why", c.why);
                x.putString("good", c.good);
                x.putString("evidence", c.evidenceWords);
                List<String> refs = new ArrayList<>();
                for (Ref r : c.words) refs.add(r.byName + (r.player ? "" : " (" + r.kind + ")") + " " + (r.weight >= 0 ? "+" : "") + r.weight);
                x.putString("refs", String.join("; ", refs));
                cs.add(x);
            }
            t.put("cands", cs);
            ListTag said = new ListTag();
            for (int i = Math.max(0, iv.said.size() - 12); i < iv.said.size(); i++) said.add(StringTag.valueOf(iv.said.get(i)));
            t.put("said", said);
            list.add(t);
        }
        out.put("interviews", list);
        out.putInt("coming", coming(village).size());
        out.putInt("held", held(village).size());
        return out;
    }

    /** /village interviews: the coming and the held, each candidate's standing. */
    public static List<String> status(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        out.add("INTERVIEWS " + Villages.name(village) + " (day " + (day + 1) + ")");
        InterviewTable.Table t = InterviewTable.known(village);
        if (t != null) {
            BlockPos c = t.cand().pos();
            out.add("  Table: " + t.where() + " at " + c.getX() + " " + c.getY() + " " + c.getZ() + (t.seated() ? "" : " (held standing)"));
        }
        List<Interview> all = new ArrayList<>(coming(village));
        all.addAll(held(village));
        if (all.isEmpty()) out.add("  None set or held.");
        for (Interview iv : all) {
            out.add("  #" + iv.id + " " + iv.stage.name().toLowerCase(Locale.ROOT) + ": " + iv.title + ", day " + (iv.dueDay + 1)
                + (iv.where.isEmpty() ? "" : " at " + iv.where) + "; panel " + iv.chairName + (iv.masterName.isEmpty() ? "" : ", " + iv.masterName)
                + (iv.councillorName.isEmpty() ? "" : ", " + iv.councillorName) + (iv.winnerName.isEmpty() ? "" : " — " + iv.winnerName + ": " + iv.reason));
            if (iv.stage.on()) {
                // Going on: who is across the table, and the last line said (the smoke stage times its pictures by it).
                Cand now = iv.now();
                String last = iv.said.isEmpty() ? "" : iv.said.get(iv.said.size() - 1).replace("|", ": ");
                out.add("     Now: " + (now == null ? iv.stage.name().toLowerCase(Locale.ROOT) : now.name + " across the table")
                    + (last.isEmpty() ? "" : "; last said, " + last));
            }
            for (Cand c : iv.cands) {
                out.add("     " + c.name + (c.outside ? " of " + c.homeName : "") + ": paper " + c.paper + (c.seen ? ", " + parts(c) + " = "
                    + (c.paper + c.interview()) : "") + (c.letter ? ", letter" : ", no letter") + (c.absent ? ", absent" : "")
                    + (c.outcome.isEmpty() ? "" : " — " + c.outcome + (c.why.isEmpty() || c.outcome.equals("chosen") ? "" : ": " + c.why)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the operator's stage, and "now"

    /**
     * /village interviews stage &lt;post&gt;: an interview set now for the post with the town's best (the posts by their
     * keys; a trade's word puts a notice up for it and the town's own stand). Lines for the camera: "TABLE x y z dx dz"
     * (the candidate's chair, and the way the panel looks) and "BENCH x y z". Null with an error in words in the list.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, String key) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Post p;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("master_")) {
            StationTask t = JobMarket.named(k.substring(7).toUpperCase(Locale.ROOT));
            p = t == null ? null : Post.master(t);
        } else {
            p = Post.of(k);
            if (p == null) {
                StationTask t = JobMarket.named(k.toUpperCase(Locale.ROOT));
                if (t != null && t != StationTask.NONE) p = Post.opening(JobMarket.postFor(level, v, t));
            }
        }
        if (p == null) {
            out.add("No such post: " + key + ". Try " + String.join(", ", InterviewPosts.keys().subList(0, 9)) + ", or a trade.");
            return out;
        }
        Interview old = pending(id, p.key());
        if (old != null) cancel(level, old, "set again by hand");
        List<VillageFolkEntity> cands;
        if (p.kind() == InterviewPosts.Kind.OPENING) {
            // For the scene: the town's own with the best hands at the trade (its own smiths too), three at most.
            StationTask want = p.tradeFor(id);
            cands = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && InterviewPosts.about(f) && !f.isElder() && !busy(f)) cands.add(f);
            }
            cands.sort(Comparator.comparingInt((VillageFolkEntity f) -> -(want == null ? 0 : f.tradeLevel(want))));
            while (cands.size() > 3) cands.remove(cands.size() - 1);
        } else {
            cands = InterviewPosts.candidates(level, v, p, null, true, Set.of());
        }
        if (cands.size() < 2) {
            out.add("Fewer than two of " + Villages.name(id) + "'s folk may stand for " + p.title(id) + ".");
            return out;
        }
        Interview iv = schedule(level, v, p, cands, List.of(), day, true);
        iv.dueTime = Math.max(iv.dueTime, (level.getDayTime() % 24000L) + 200);
        STAGED.put(id, iv.id);
        // For the scene: a smith or a tailor with no piece of its own work to hand is given one in its name to hold up
        // (from the stage's palette, as every stage sets its scene, not out of the stores).
        StationTask craft = p.tradeFor(id);
        boolean any = false;
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f != null && InterviewScript.piece(level, iv, f) != null) any = true;
        }
        if ((craft == StationTask.SMITH || craft == StationTask.TAILOR) && !any) {
            for (Cand c : iv.cands) {
                VillageFolkEntity f = Civics.find(level, c.id);
                if (f == null || InterviewScript.piece(level, iv, f) != null) continue;
                ItemStack made = new ItemStack(craft == StationTask.SMITH ? net.minecraft.world.item.Items.IRON_SWORD
                    : net.minecraft.world.item.Items.LEATHER_CHESTPLATE);
                Craftsmanship.finish(level, made, Math.max(1, f.tradeLevel(craft)), f.displayNameCap());
                ItemStack left = f.insertItem(made);
                if (left.isEmpty()) out.add("PROP " + c.name + " has " + InterviewScript.article(made) + " of its own make to show (the stage's)");
                break;
            }
        }
        InterviewTable.Table t = InterviewTable.ready(level, v);
        iv.table = t;
        out.add("Set: #" + iv.id + " " + iv.title + " in " + Villages.name(id) + ": " + JobMarket.join(names(iv)) + "; panel " + iv.chairName
            + (iv.masterName.isEmpty() ? "" : ", " + iv.masterName) + (iv.councillorName.isEmpty() ? "" : ", " + iv.councillorName));
        if (t != null) {
            BlockPos c = t.cand().pos();
            out.add("TABLE " + c.getX() + " " + c.getY() + " " + c.getZ() + " " + -t.cand().look().getStepX() + " " + -t.cand().look().getStepZ());
            BlockPos b = t.bench().get(t.bench().size() / 2).pos();
            out.add("BENCH " + b.getX() + " " + b.getY() + " " + b.getZ());
            out.add("WHERE " + t.where());
            // The camera's places (the smoke stage): the panel looks toward the candidate's chair, and the bench is behind it
            // (by the board) or at the table's foot (in the hall).
            net.minecraft.core.Direction dir = t.cand().look().getOpposite(), side = dir.getClockWise();
            BlockPos head = t.panel().get(Math.min(1, t.panel().size() - 1)).pos();
            int toBench = (b.getX() - c.getX()) * side.getStepX() + (b.getZ() - c.getZ()) * side.getStepZ();
            if (toBench < 0) side = side.getOpposite();                           // looking from the bench's end of the table
            BlockPos mid = new BlockPos(Math.floorDiv(c.getX() + head.getX(), 2), c.getY(), Math.floorDiv(c.getZ() + head.getZ(), 2));
            view(out, "iv-bench", t.hall() ? t.out().above(2) : b.relative(dir.getOpposite(), 2).relative(side, 2).above(), b.above());
            view(out, "iv-table", mid.relative(side, 5).above(2), mid.above());
            view(out, "iv-shoulder", c.relative(dir, 2).relative(side).above(), head.above());
            view(out, "iv-panel", head.relative(dir.getOpposite(), 2).relative(side).above(), c.above());
        } else {
            out.add("No table yet: a hand is being sent to set one out by the board.");
        }
        return out;
    }

    /** "VIEW name x y z ax ay az": a camera's place and what it looks at, as the other stages give them. */
    private static void view(List<String> out, String name, BlockPos from, BlockPos at) {
        out.add("VIEW " + name + " " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    /** /village interviews now: the town's next interview begun at once (those from away set off, or their letters read). */
    public static String now(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (running(id) != null) return "An interview is on already: " + running(id).title + ".";
        List<Interview> coming = coming(id);
        if (coming.isEmpty()) return "No interview set in " + Villages.name(id) + ".";
        Interview iv = coming.get(0);
        long dt = level.getDayTime();
        iv.dueDay = dt / 24000L;
        iv.dueTime = dt % 24000L;
        for (Cand c : iv.cands) {
            VillageFolkEntity f = Civics.find(level, c.id);
            if (f == null) continue;
            if (!c.letter) write(level, iv, c, f);
            if (c.outside && !c.setOff && !c.arrived) setOff(level, v, iv, c, f);
        }
        boolean away = false;
        for (Cand c : iv.cands) if (c.outside && !c.arrived && c.setOff) away = true;
        if (away) {
            iv.dueTime = Math.min(LATEST - 1, iv.dueTime + 1200);              // a minute for those on the road
            InterviewBook.changed();
            return "The interviews for " + iv.title + " begin in a minute: those from away are on the road.";
        }
        String no = notNow(level, v);
        if (no != null) return "Not now: " + no + ".";
        begin(level, v, iv);
        return "The interviews for " + iv.title + " begin now at " + iv.where + ".";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the interview set for a post now (the town's best, three at most), as the stage sets it. */
    @Nullable
    public static Interview stageForTests(ServerLevel level, Villages.Village v, String key) {
        STAGED.remove(v.id());
        for (String l : stage(level, v, key)) LOG.info("[MCA-INTERVIEW] stage: {}", l);
        Integer id = STAGED.get(v.id());
        return id == null ? null : find(v.id(), id);
    }

    /** Tests: the next interview begun now (or its travellers set off). */
    public static String nowForTests(ServerLevel level, Villages.Village v) {
        return now(level, v);
    }

    /** Tests: the interview on or coming, for a post's key ("teacher", "opening:3"). */
    @Nullable
    public static Interview pendingForTests(UUID village, String post) {
        return pending(village, post);
    }

    /** Tests: the latest held for a post. */
    @Nullable
    public static Interview heldForTests(UUID village, String post) {
        for (Interview iv : held(village)) if (iv.post.equals(post)) return iv;
        return null;
    }

    /** Tests: a candidate's word put in by a player, as the talk screen takes it. */
    public static String recommendForTests(ServerLevel level, ServerPlayer p, UUID village, String text) {
        return recommend(level, p, village, text, null);
    }

    /** Tests: one of the town's notices weighed by its leader now, an interview set if two or more would do. */
    public static int considerForTests(ServerLevel level, Villages.Village v) {
        boolean was = auto;
        auto = true;
        try {
            return JobMarket.decide(level, v, false);
        } finally {
            auto = was;
        }
    }

    /** Tests: an application from this folk to the town's notice, with its reasons as it would give them. */
    public static void applyForTests(ServerLevel level, VillageFolkEntity f, UUID town, JobMarket.Opening o) {
        JobSeekers.Reasons r = JobSeekers.reasons(f, town, o);
        JobMarket.apply(level, f, town, o, r.why().isEmpty() ? new JobSeekers.Reasons(List.of(JobSeekers.Why.START), 1, "young, wanting a start",
            "for a start in life") : r);
    }

    /** Tests: the interview a second (or many) on, as the clock would have it. */
    public static void stepForTests(ServerLevel level, Villages.Village v) {
        step(level, v);
    }

    /** Tests: the panel's choice for a post the town keeps (the constable, the cave team's leader), loaded; or null. */
    @Nullable
    public static VillageFolkEntity holderForTests(UUID village, String post) {
        return holder(village, post);
    }

    /** Tests: the constable as the watch's own reckoning has it (Inquiry), the panel's choice first. */
    @Nullable
    public static VillageFolkEntity constableForTests(UUID village) {
        return Inquiry.constable(village);
    }

    /** Tests: who has sat in the candidate's chair this sitting, in order. */
    public static List<UUID> satForTests(Interview iv) {
        return new ArrayList<>(iv.sat);
    }

    /** Tests: the table the interview is held at: the candidate's chair, the chair's, and the bench's first seat. */
    @Nullable
    public static BlockPos[] tableForTests(Interview iv) {
        InterviewTable.Table t = iv.table;
        return t == null ? null : new BlockPos[]{ t.cand().pos(), t.chairSeat().pos(), t.bench().get(0).pos() };
    }

    /** Tests: where the interview is held ("the meeting hall", "the board"), and whether there are chairs. */
    public static String whereForTests(Interview iv) {
        return iv.table == null ? "" : iv.table.where() + (iv.table.seated() ? "" : " (standing)");
    }

    /** Tests: the role an interview gives a folk now ("panel", "candidate", "referee"), or "". */
    public static String roleForTests(VillageFolkEntity f) {
        Role r = ROLES.get(f.getUUID());
        if (r == null) return VISITS.containsKey(f.getUUID()) ? "road" : "";
        Interview iv = find(r.village(), r.id());
        if (iv == null) return "";
        if (f.getUUID().equals(iv.chair) || f.getUUID().equals(iv.master) || f.getUUID().equals(iv.councillor)) return "panel";
        return iv.cand(f.getUUID()) != null ? "candidate" : "referee";
    }

    /** Tests: the folk sat on the bench (or its spot) for an interview, its letter in hand. */
    public static boolean onTheBenchForTests(VillageFolkEntity f) {
        Role r = ROLES.get(f.getUUID());
        Interview iv = r == null ? null : find(r.village(), r.id());
        if (iv == null || iv.table == null) return false;
        Cand c = iv.cand(f.getUUID());
        return c != null && near(f, InterviewTable.spot(iv.table, bench(iv, c)), 1.6);
    }

    // ------------------------------------------------------------------ words

    private static String capital(String s) {
        return InterviewScript.capital(s);
    }

    private static String clip(String s, int most) {
        return InterviewScript.clip(s, most);
    }
}
