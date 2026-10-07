package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [civic] The whole town's vote: on a great work (BigWorks), and on newcomers who ask to settle (Newcomers).
 *
 * <p><b>Called.</b> A great work is put to the town by its leader (or, with none, the one the council thinks most
 * of) two days before the vote: what it is and where, its cost out of the stores, the labour and what it brings,
 * all on the board. A party of newcomers camped at the town's edge is put to it the day they come (the next, if they
 * come after noon).
 *
 * <p><b>The campaign.</b> Folk argue it by what they care about (Values): the thrifty and the old-fashioned against
 * the cost, the Visionaries for anything the town builds, the Guardians for a wall, the farmers for water to the
 * fields, the fishers for a harbour, the Merchants for a road; hungry folk against building anything while the larder
 * is bare. They say so to whoever is about, to each other (Smalltalk), and to you when you ask about the council.
 * Newcomers are weighed by the room the town has, its food, its mood and its leader's temper (Newcomers.judge).
 *
 * <p><b>The vote.</b> On the day each grown folk walks to the board at an hour of its own, as at an election, and
 * votes aye or nay, telling whoever is by why. A player who is a citizen of the town has a vote too
 * ("/village referendum vote aye", or tell any folk "I vote aye"). In the evening the town gathers at the board for
 * the count (an Assemblies gathering): a voice for each side, the tally, and the result; if the evening is taken by
 * something else, the votes are counted quietly after dark. Carried, a work starts the next morning; lost, it waits a
 * season (seven days) before it may be put again. Newcomers voted in are taken in; voted out, they move on.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Referendums {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Referendums() {}

    /** What is put to the town: a great work, or newcomers asking to settle. */
    static final String WORKS = "WORKS", REFUGE = "REFUGE";
    /** Days from a great work's being called to the vote. */
    static final int CAMPAIGN = 2;
    /** The polls: open from the morning, closed before the count; counted quietly after dark if nobody gathered. */
    static final long OPEN = 1000L, CLOSE = 11900L, COUNT_AT = 13400L;
    /** A work voted down waits a season before it may be put again; one vote on the works, then three days' rest. */
    static final int WAIT = Seasons.DAYS, BETWEEN = 3;
    /** The round of the towns: every five seconds. */
    static final int EVERY = 100;

    /** Folk on their way to the board: when they set out (game time). */
    private static final Map<UUID, Long> WALKING = new ConcurrentHashMap<>();
    /** The day each folk last spoke its mind on the question aloud. */
    private static final Map<UUID, Long> SPOKE = new ConcurrentHashMap<>();

    /** Everything forgotten (Villages.resetForTests: the tests share one world). */
    public static void resetForTests() {
        CivicRecord.wipeForTests();
        WALKING.clear();
        SPOKE.clear();
        BigWorks.resetForTests();
        Newcomers.resetForTests();
    }

    // ------------------------------------------------------------------ the round of the towns

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 61) return;
        com.jrpetty.mcassistant.Guard.run("the town's votes", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** One look at a town: its newcomers, its work under way, its open questions, and whether to put a work to it. */
    public static void tick(ServerLevel level, Villages.Village v) {
        com.jrpetty.mcassistant.Guard.run("newcomers", () -> Newcomers.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the great works", () -> BigWorks.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the questions", () -> questions(level, v));
        com.jrpetty.mcassistant.Guard.run("calling a vote", () -> consider(level, v));
    }

    /** The open questions looked at: announced on the day, argued over, and counted quietly if the evening passed. */
    static void questions(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        for (CompoundTag q : open(id)) {
            long vote = q.getLong("vote");
            if (!q.getBoolean("counted") && (day > vote || day == vote && t >= COUNT_AT)) {
                count(level, v, q, day);
                decide(level, v, q, day);
                continue;
            }
            if (day == vote && q.getLong("announced") != day + 1 && t >= 100L && t < 1300L) {
                q.putLong("announced", day + 1);
                CivicRecord.changed();
                Market.assemblyNews(id, "Today we vote on " + q.getString("title") + ". Go to the board when your work allows, and say aye or nay.");
            }
        }
        // A decision counted at the gathering but not yet acted on (the gathering broken off): acted on now.
        for (Tag tg : list(id)) {
            if (tg instanceof CompoundTag q && q.getBoolean("counted") && !q.getBoolean("applied") && (day > q.getLong("vote") || t >= COUNT_AT + 200L)) {
                decide(level, v, q, day);
            }
        }
        campaign(level, v, day, t);
    }

    /** Once a day, in the morning: the leader puts the town's most wanted great work to it, if nothing else is in hand. */
    static void consider(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < 1500L || t > 9000L) return;
        CompoundTag town = CivicRecord.town(id);
        if (town.getLong("considered") == day + 1) return;
        town.putLong("considered", day + 1);
        CivicRecord.changed();
        if (BigWorks.underWay(id) || Raids.underAlarm(id)) return;
        for (CompoundTag q : open(id)) if (WORKS.equals(q.getString("kind"))) return;
        if (town.contains("lastWorksVote") && day - town.getLong("lastWorksVote") < BETWEEN) return;
        if (Elections.voters(id).size() < BigWorks.GROWN_AT_LEAST) return;
        for (BigWorks.Proposal p : BigWorks.proposals(level, v)) {
            if (waitsUntil(id, p.kind()) > day) continue;
            callWorks(level, v, p, day, day + CAMPAIGN);
            return;
        }
    }

    /** The day until which a work voted down waits (0: it does not). */
    static long waitsUntil(UUID village, BigWorks.Work kind) {
        return CivicRecord.sub(CivicRecord.town(village), "waits").getLong(kind.name());
    }

    // ------------------------------------------------------------------ the questions

    static ListTag list(UUID village) {
        return CivicRecord.list(CivicRecord.town(village), "questions");
    }

    /** The questions not yet counted. */
    static List<CompoundTag> open(UUID village) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : list(village)) if (t instanceof CompoundTag q && !q.getBoolean("counted")) out.add(q);
        return out;
    }

    @Nullable
    static CompoundTag question(UUID village, int id) {
        for (Tag t : list(village)) if (t instanceof CompoundTag q && q.getInt("id") == id) return q;
        return null;
    }

    /** Who calls it: the leader, or the one the council thinks most of. */
    @Nullable
    static VillageFolkEntity caller(UUID village) {
        VillageFolkEntity e = Orders.elderOf(village);
        if (e != null && !e.isBaby()) return e;
        List<VillageFolkEntity> council = Council.members(village);
        return council.isEmpty() ? null : council.get(0);
    }

    /** A great work put to the town: on the board, in the chronicle and at the morning assembly, the vote in two days. */
    static CompoundTag callWorks(ServerLevel level, Villages.Village v, BigWorks.Proposal p, long day, long voteDay) {
        VillageFolkEntity by = caller(v.id());
        CompoundTag q = call(level, v, WORKS, p.kind().name(), p.title(), by == null ? "" : by.displayNameCap(), p.costWords(), p.labour(),
            p.brings(), voteDay, BigWorks.spec(v, p));
        if (by != null) q.putString("byId", by.getUUID().toString());
        CivicRecord.town(v.id()).putLong("lastWorksVote", day);
        CivicRecord.changed();
        String when = voteDay <= day ? "today" : voteDay == day + 1 ? "tomorrow" : "in " + (voteDay - day) + " days";
        Villages.tell(v.id(), day, (by == null ? "the council" : by.displayNameCap()) + " put " + p.title() + " to the town, the vote "
            + when + ": " + p.costWords() + " from the stores");
        Market.assemblyNews(v.id(), "A vote " + when + ": shall we build " + p.title() + "? It would cost " + p.costWords()
            + ", and bring " + p.brings() + ". Think it over!");
        if (by != null) {
            FolkTalk.speak(by, "I'm putting it to all of you: " + p.title() + ". Vote " + when + " at the board!");
            by.persona().remember(day, "I put " + p.title() + " to the town", 4);
        }
        LOG.info("[MCA-CIVIC] {}: a vote called on {} for day {} ({}; {})", Villages.name(v.id()), p.title(), voteDay, p.costWords(), p.labour());
        return q;
    }

    /** A question put to the town, written down. */
    static CompoundTag call(ServerLevel level, Villages.Village v, String kind, String subject, String title, String by, String cost,
                            String labour, String brings, long voteDay, @Nullable CompoundTag spec) {
        long day = level.getDayTime() / 24000L;
        CompoundTag q = new CompoundTag();
        q.putInt("id", CivicRecord.nextId());
        q.putString("kind", kind);
        q.putString("subject", subject);
        q.putString("title", title);
        q.putString("by", by);
        q.putLong("called", day);
        q.putLong("vote", voteDay);
        q.putString("cost", cost);
        q.putString("labour", labour);
        q.putString("brings", brings);
        if (spec != null) q.put("spec", spec);
        q.put("ballots", new CompoundTag());
        q.put("players", new CompoundTag());
        ListTag all = list(v.id());
        all.add(q);
        while (all.size() > 12) {
            if (all.get(0) instanceof CompoundTag old && !old.getBoolean("counted")) break;
            all.remove(0);
        }
        CivicRecord.changed();
        return q;
    }

    // ------------------------------------------------------------------ how a folk votes

    /** How a folk weighs a question: aye or nay, how strongly, and the reason it gives. */
    public record Judged(boolean aye, int score, String why) {}

    static Judged judge(ServerLevel level, VillageFolkEntity voter, CompoundTag q) {
        if (REFUGE.equals(q.getString("kind"))) return Newcomers.judge(level, voter, q.getString("subject"));
        return judgeWorks(level, voter, q);
    }

    /**
     * A great work, weighed by what the folk cares about against what it costs: anything the town builds for the
     * Visionary; the wall for the Guardian (the more after a raid, or in a war); water for the farmer and the Provider;
     * a harbour for the fisher; a road or a bridge to a neighbour for the Merchant; a word from the one who called it
     * for its friends. Against it: the cost, for the thrifty and the old-fashioned, the more of the stores it would take;
     * and in hard times, anything but food.
     */
    static Judged judgeWorks(ServerLevel level, VillageFolkEntity voter, CompoundTag q) {
        UUID id = voter.ownerId();
        BigWorks.Work kind = BigWorks.Work.named(q.getString("subject"));
        if (kind == null || id == null) return new Judged(false, 0, "I don't know what it's for");
        int[] w = Values.of(voter);
        AssistantEntity.StationTask trade = voter.stationTask();
        double forIt = w[Values.Value.PROGRESS.ordinal()] * 0.30;
        String whyFor = "a town that builds like that is going places";
        double most = forIt;
        CompoundTag spec = q.getCompound("spec");
        String where = spec.getString("where");
        double extra = 0;
        String extraWhy = null;
        switch (kind) {
            case BRIDGE -> {
                if (trade == AssistantEntity.StationTask.FARM && where.contains("fields")) { extra = 30; extraWhy = "I'd be at the fields in half the time"; }
                else if (where.contains("on the way to")) { extra = w[Values.Value.WEALTH.ordinal()] * 0.30; extraWhy = "a dry crossing means trade with the neighbours"; }
                else { extra = w[Values.Value.HOMES.ordinal()] * 0.15; extraWhy = "there's room to build across the river"; }
            }
            case AQUEDUCT, CANAL -> {
                extra = w[Values.Value.FOOD.ordinal()] * 0.40 + w[Values.Value.LEISURE.ordinal()] * 0.10;
                if (trade == AssistantEntity.StationTask.FARM) extra += 25;
                extraWhy = trade == AssistantEntity.StationTask.FARM ? "water for the fields — I'd vote for that twice" : "water to the town is food on the table";
            }
            case WALL -> {
                extra = w[Values.Value.SAFETY.ordinal()] * 0.55;
                long raided = Raids.raidedOn(id);
                long day = level.getDayTime() / 24000L;
                if (raided >= 0 && day - raided <= 21) extra += 20;
                if (Wars.footing(id) == Wars.Footing.WAR) extra += 25;
                if (trade == AssistantEntity.StationTask.GUARD) extra += 25;
                extraWhy = raided >= 0 && day - raided <= 21 ? "after that raid? A wall, and the sooner the better" : "a wall's what lets us sleep at night";
            }
            case HARBOUR -> {
                extra = w[Values.Value.WEALTH.ordinal()] * 0.30 + (trade == AssistantEntity.StationTask.FISH ? 30 : 0);
                extraWhy = trade == AssistantEntity.StationTask.FISH ? "a proper quay at last, and deep water off the end of it" : "a harbour brings trade by water";
            }
            case ROAD -> {
                extra = w[Values.Value.WEALTH.ordinal()] * 0.40;
                extraWhy = "a good road means trade, and trade means wages";
            }
        }
        forIt += extra;
        if (extra > most && extraWhy != null) { most = extra; whyFor = extraWhy; }
        // A word from the one who called it, for those who think well of it.
        String byId = q.getString("byId"), byName = q.getString("by");
        if (byId.equals(voter.getUUID().toString())) {
            forIt += 40;
            whyFor = "I put it to you myself, and I meant it";
        } else if (!byId.isEmpty()) {
            double word;
            try {
                word = voter.life().affinity(UUID.fromString(byId)) * 0.15;
            } catch (IllegalArgumentException e) {
                word = 0;
            }
            forIt += word;
            if (word > most) { most = word; whyFor = "if " + byName + " says we need it, that's good enough for me"; }
        }
        long day = level.getDayTime() / 24000L;
        forIt += Math.floorMod(Objects.hash(voter.getUUID(), q.getInt("id"), day), 9);
        // Against: the cost, as a share of what the stores hold, for the thrifty; and hard times.
        WorksPlans.Family family = WorksPlans.Family.named(spec.getString("family"));
        int units = Math.max(1, spec.getInt("units"));
        int have = family == null ? 0 : Market.stock(level, id, family.payment());
        double share = Math.min(1.6, 0.3 + units / (double) Math.max(1, have));
        double thrift = w[Values.Value.TRADITION.ordinal()] * 0.45 + (voter.life().has(Social.Trait.GRUMPY) ? 10 : 0)
            + (voter.life().has(Social.Trait.GENEROUS) ? -6 : 0) + (Wealth.tier(voter) == Wealth.Tier.POOR ? 8 : 0);
        double against = Math.max(0, thrift) * share;
        String whyNot = units + " " + (family == null ? "stone" : family.words) + "? The stores would be bare for a month";
        if (w[Values.Value.TRADITION.ordinal()] >= 50) whyNot = "we've managed without " + kind.a.replaceFirst("^(a|an|the) ", "a ") + " this long";
        Leader.Plan plan = Leader.plan(id);
        boolean water = kind == BigWorks.Work.AQUEDUCT || kind == BigWorks.Work.CANAL;
        if (!water && (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT)) {
            double hard = plan == Leader.Plan.FAMINE ? 35 : 15;
            against += hard;
            if (hard >= against / 2) whyNot = "build " + kind.the + " while the larder's empty? Feed us first";
        }
        boolean aye = forIt > against;
        return new Judged(aye, (int) Math.round(forIt - against), aye ? whyFor : whyNot);
    }

    // ------------------------------------------------------------------ voting

    /** The grown folk of the town who may vote. */
    static List<VillageFolkEntity> voters(UUID village) {
        return Elections.voters(village);
    }

    /** A folk casts its vote on a question, at the board or from wherever it is. */
    static void cast(ServerLevel level, CompoundTag q, VillageFolkEntity voter, boolean atTheBoard) {
        CompoundTag ballots = q.getCompound("ballots");
        String key = voter.getUUID().toString();
        if (ballots.contains(key)) return;
        Judged j = judge(level, voter, q);
        CompoundTag b = new CompoundTag();
        b.putBoolean("aye", j.aye());
        b.putString("why", j.why());
        b.putBoolean("board", atTheBoard);
        b.putString("name", voter.displayNameCap());
        ballots.put(key, b);
        q.put("ballots", ballots);
        CivicRecord.changed();
        long day = level.getDayTime() / 24000L;
        voter.persona().remember(day, "I voted " + (j.aye() ? "aye" : "nay") + " on " + q.getString("title") + ": " + j.why(), 2);
        if (!atTheBoard) return;
        voter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.sendParticles(j.aye() ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE, voter.getX(), voter.getY() + 2.1, voter.getZ(),
            5, 0.3, 0.2, 0.3, 0.02);
        level.playSound(null, voter.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.8F, 1.0F);
        FolkTalk.speak(voter, (j.aye() ? FolkTalk.pick(level.getRandom(), "Aye from me", "Aye", "I say aye")
            : FolkTalk.pick(level.getRandom(), "Nay from me", "Nay", "I say nay")) + " — " + j.why() + ".");
    }

    /** The questions this folk has still to vote on today. */
    static List<CompoundTag> dueFor(VillageFolkEntity f, long day) {
        List<CompoundTag> out = new ArrayList<>();
        UUID id = f.ownerId();
        if (id == null) return out;
        String key = f.getUUID().toString();
        for (CompoundTag q : open(id)) if (q.getLong("vote") == day && !q.getCompound("ballots").contains(key)) out.add(q);
        return out;
    }

    /**
     * Every few ticks for each folk (VillageFolkEntity.aiStep): on a vote day, at its own hour and when its work allows,
     * it walks to the board and votes on whatever is put to the town. True while it is about it.
     */
    public static boolean goVote(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.isShowcase() || !f.persona().rolled()) return false;
        long dayTime = level.getDayTime(), day = dayTime / 24000L, t = dayTime % 24000L, now = level.getGameTime();
        if (t < OPEN || t >= CLOSE) {
            WALKING.remove(f.getUUID());
            return false;
        }
        List<CompoundTag> due = dueFor(f, day);
        if (due.isEmpty()) {
            WALKING.remove(f.getUUID());
            return false;
        }
        if (f.isSleeping() || f.isHired() || f.getTarget() != null || Raids.underAlarm(id) || Assemblies.attending(f)
                || Nether.away(f) || Drover.busy(f) || Scouts.out(f) || f.trip() != null || BigWorks.busy(f)) {
            return false;
        }
        Long since = WALKING.get(f.getUUID());
        if (since == null) {
            long turn = OPEN + Math.floorMod(f.getUUID().getMostSignificantBits(), CLOSE - OPEN - 2500L);
            if (t < turn) return false;
            if (f.peekJob() != null && t < Math.min(CLOSE - 1500L, turn + 2500L)) return false;
            f.clearQueue();
            f.getNavigation().stop();
            WALKING.put(f.getUUID(), now);
            since = now;
            if (level.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Off to have my say.", "Time I voted.",
                "Back soon: the vote."));
        }
        Villages.Village v = Villages.get(id);
        BlockPos board = VillageBoards.lectern(id);
        if (board == null) board = v != null ? v.centre() : f.blockPosition();
        double d2 = f.blockPosition().distSqr(board);
        long gone = now - since;
        if (d2 <= 2.5 * 2.5 || gone > 1600L && d2 <= 10 * 10) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(board.getX() + 0.5, board.getY() + 1.5, board.getZ() + 0.5);
            for (CompoundTag q : due) cast(level, q, f, true);
            WALKING.remove(f.getUUID());
            f.hobbyNow = "voted";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        if (gone > 3600L) {                                        // could not get there: sends its vote with a neighbour
            for (CompoundTag q : due) cast(level, q, f, false);
            WALKING.remove(f.getUUID());
            return false;
        }
        if (f.getNavigation().isDone() || gone % 60L == 0) f.walkTo(board, 0.9D);
        f.hobbyNow = "on the way to vote";
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /**
     * A player's vote: a citizen's counts, on what is put to the town today (or, ahead of the day, on the open
     * question, as a vote sent in early). Returns what was said.
     */
    public static String playerVote(ServerLevel level, Villages.Village v, Player p, boolean aye) {
        UUID id = v.id();
        String town = Villages.name(id);
        if (!Ledger.citizen(id, p.getUUID())) return "Only the citizens of " + town + " have a vote here. Ask any folk \"May I live here?\".";
        List<CompoundTag> qs = open(id);
        if (qs.isEmpty()) return "Nothing is put to " + town + " just now.";
        long day = level.getDayTime() / 24000L;
        CompoundTag q = qs.get(0);
        for (CompoundTag o : qs) if (o.getLong("vote") == day) { q = o; break; }
        CompoundTag players = q.getCompound("players");
        CompoundTag b = new CompoundTag();
        b.putBoolean("aye", aye);
        b.putString("name", p.getName().getString());
        players.put(p.getUUID().toString(), b);
        q.put("players", players);
        CivicRecord.changed();
        LOG.info("[MCA-CIVIC] {}: citizen {} votes {} on {}", town, p.getName().getString(), aye ? "aye" : "nay", q.getString("title"));
        return "Your vote is in: " + (aye ? "aye" : "nay") + " on " + q.getString("title") + ". The count is "
            + (q.getLong("vote") == day ? "this evening." : "on day " + (q.getLong("vote") + 1) + ".");
    }

    /** "I vote aye", said to a folk (FolkTalk): the player's vote, if that is what the words are; else null. */
    @Nullable
    public static String playerSays(VillageFolkEntity f, Player p, String text) {
        if (text == null || text.isEmpty() || f.ownerId() == null || !(f.level() instanceof ServerLevel level)) return null;
        String t = text.toLowerCase(Locale.ROOT);
        if (!t.contains("vote")) return null;                  // ("vote for a tavern" is a word to the council: Council.propose)
        boolean aye = t.contains("aye") || t.contains("vote yes");
        boolean nay = t.contains("nay") || t.contains("vote no");
        if (aye == nay) return null;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        return playerVote(level, v, p, aye);
    }

    // ------------------------------------------------------------------ the count

    /** Is something to be counted this evening? */
    static boolean countsToday(UUID village, long day) {
        for (CompoundTag q : open(village)) if (q.getLong("vote") == day) return true;
        return false;
    }

    /** Is the town to gather this evening for a count, or to open a finished work (Assemblies)? */
    public static boolean gatheringDue(UUID village, long day) {
        return countsToday(village, day) || BigWorks.openingDue(village, day);
    }

    /** The evening's gathering: the count at the board, else the opening at the work's end. */
    static Assemblies.Assembly assembly(ServerLevel level, Villages.Village v, long day) {
        if (!countsToday(v.id(), day)) return BigWorks.opening(level, v, day);
        BlockPos lectern = VillageBoards.lectern(v.id());
        Direction facing = VillageBoards.facingOf(v.id());
        return new Assemblies.Assembly(v.id(), Assemblies.Kind.REFERENDUM, "count", day, lectern != null ? lectern : v.centre(),
            facing != null ? facing : Direction.SOUTH, Assemblies.Layout.ARC);
    }

    /** What the gathering is, in a few words (Assemblies.describe): "the count of the town's vote", "the opening of the bridge". */
    static String describe(Assemblies.Assembly a) {
        if (!"open".equals(a.subject)) return "the count of the town's vote";
        CompoundTag w = BigWorks.current(a.village);
        BigWorks.Work kind = w == null ? null : BigWorks.Work.named(w.getString("kind"));
        return "the opening of " + (kind == null ? "the town's great work" : kind.the);
    }

    /** The gathering's lines (Assemblies.script): the count, each side heard, and the result; or the opening. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        if ("open".equals(a.subject)) {
            BigWorks.openingScript(level, a, s, r);
            return;
        }
        Villages.Village v = Villages.get(a.village);
        if (v == null) return;
        long day = a.day;
        for (CompoundTag q : open(v.id())) {
            if (q.getLong("vote") != day) continue;
            count(level, v, q, day);
            boolean refuge = REFUGE.equals(q.getString("kind"));
            s.add(new Assemblies.Line(null, refuge ? "Friends — " + q.getString("title") + ". You have all had your say."
                : "Friends — the count on " + q.getString("title") + ".", '~', null));
            // A voice for each side, in its own words.
            UUID ayeVoice = null, nayVoice = null;
            String ayeWhy = "", nayWhy = "";
            CompoundTag ballots = q.getCompound("ballots");
            for (String k : ballots.getAllKeys()) {
                CompoundTag b = ballots.getCompound(k);
                UUID u;
                try { u = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
                if (u.equals(a.host)) continue;
                if (b.getBoolean("aye") && ayeVoice == null) { ayeVoice = u; ayeWhy = b.getString("why"); }
                if (!b.getBoolean("aye") && nayVoice == null) { nayVoice = u; nayWhy = b.getString("why"); }
            }
            if (ayeVoice != null) s.add(new Assemblies.Line(ayeVoice, "Aye, I say: " + ayeWhy + "!", '!', null));
            if (nayVoice != null) s.add(new Assemblies.Line(nayVoice, "And I say nay: " + nayWhy + ".", '?', null));
            int ayes = q.getInt("ayes"), nays = q.getInt("nays");
            boolean carried = q.getBoolean("carried");
            String result = refuge
                ? (carried ? "We take them in. Welcome to " + Villages.name(v.id()) + "!" : "We cannot take them in. I'm sorry.")
                : (carried ? "It is carried! We start in the morning — everybody lend a hand." : ayes == nays ? "A tie: it falls. We'll put it again another season."
                    : "It falls. We'll think again next season.");
            CompoundTag fq = q;
            s.add(new Assemblies.Line(null, "Aye " + ayes + ", nay " + nays + ". " + result, carried ? '!' : '?', () -> decide(level, v, fq, day)));
        }
    }

    /**
     * The count: any grown folk about the town that never got to the board votes now, as it stands; the players'
     * votes are added in; carried on more ayes than nays (a tie falls). Into the books; acted on by decide.
     */
    static void count(ServerLevel level, Villages.Village v, CompoundTag q, long day) {
        if (q.getBoolean("counted")) return;
        for (VillageFolkEntity f : voters(v.id())) {
            if (!q.getCompound("ballots").contains(f.getUUID().toString()) && f.blockPosition().distSqr(v.centre()) < 112 * 112) cast(level, q, f, false);
        }
        int ayes = 0, nays = 0;
        CompoundTag ballots = q.getCompound("ballots");
        for (String k : ballots.getAllKeys()) if (ballots.getCompound(k).getBoolean("aye")) ayes++; else nays++;
        CompoundTag players = q.getCompound("players");
        for (String k : players.getAllKeys()) if (players.getCompound(k).getBoolean("aye")) ayes++; else nays++;
        q.putInt("ayes", ayes);
        q.putInt("nays", nays);
        q.putBoolean("carried", ayes > nays);
        q.putBoolean("counted", true);
        q.putLong("countedOn", day);
        ListTag past = CivicRecord.list(CivicRecord.town(v.id()), "votesPast");
        CompoundTag p = new CompoundTag();
        p.putLong("day", day);
        p.putString("kind", q.getString("kind"));
        p.putString("title", q.getString("title"));
        p.putInt("ayes", ayes);
        p.putInt("nays", nays);
        p.putBoolean("carried", ayes > nays);
        past.add(p);
        while (past.size() > 20) past.remove(0);
        CivicRecord.changed();
        Villages.tell(v.id(), day, "the town voted " + ayes + " to " + nays + (ayes > nays ? " for " : " against ")
            + (REFUGE.equals(q.getString("kind")) ? "taking in " : "") + q.getString("title"));
        LOG.info("[MCA-CIVIC] {}: the count on {}: aye {}, nay {} — {}", Villages.name(v.id()), q.getString("title"), ayes, nays,
            ayes > nays ? "carried" : "lost");
    }

    /** What the count decided, done (once): a work started or put off a season; newcomers taken in or sent on. */
    static void decide(ServerLevel level, Villages.Village v, CompoundTag q, long day) {
        if (!q.getBoolean("counted") || q.getBoolean("applied")) return;
        q.putBoolean("applied", true);
        CivicRecord.changed();
        boolean carried = q.getBoolean("carried");
        if (REFUGE.equals(q.getString("kind"))) {
            Newcomers.decided(level, v, q.getString("subject"), carried, q.getInt("ayes"), q.getInt("nays"));
            return;
        }
        BigWorks.Work kind = BigWorks.Work.named(q.getString("subject"));
        if (carried && !BigWorks.underWay(v.id())) {
            BigWorks.start(level, v, q.getCompound("spec"), day);
        } else if (kind != null) {
            CivicRecord.sub(CivicRecord.town(v.id()), "waits").putLong(kind.name(), day + WAIT);
            CivicRecord.changed();
            Villages.tell(v.id(), day, kind.a + " waits a season: the town will not be asked again before day " + (day + WAIT + 1));
        }
    }

    // ------------------------------------------------------------------ the campaign

    /** Now and then, a folk off work says aloud where it stands on what is to be voted on (once a day each). */
    static void campaign(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 1500L || t > 12000L || level.getRandom().nextInt(3) != 0) return;
        List<CompoundTag> qs = open(v.id());
        if (qs.isEmpty()) return;
        CompoundTag q = qs.get(level.getRandom().nextInt(qs.size()));
        if (day < q.getLong("called") || day > q.getLong("vote")) return;
        List<VillageFolkEntity> folk = voters(v.id());
        if (folk.isEmpty()) return;
        VillageFolkEntity f = folk.get(level.getRandom().nextInt(folk.size()));
        Long said = SPOKE.get(f.getUUID());
        if (said != null && said == day || !f.offWorkNow() || f.isSleeping() || f.talkPartner() != null) return;
        SPOKE.put(f.getUUID(), day);
        Judged j = judge(level, f, q);
        String what = REFUGE.equals(q.getString("kind")) ? "those folk at the edge of town" : BigWorks.Work.named(q.getString("subject")) == null
            ? "it" : BigWorks.Work.named(q.getString("subject")).the;
        FolkTalk.speak(f, (j.aye() ? FolkTalk.pick(level.getRandom(), "I'm voting aye on " + what + ": ", "Aye on " + what + ", I say: ")
            : FolkTalk.pick(level.getRandom(), "I'm voting nay on " + what + ": ", "Nay on " + what + ", I say: ")) + j.why() + ".");
    }

    /** What a folk says of the vote when asked about the council (FolkTalk): "" when nothing is put to the town. */
    public static String talk(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return "";
        List<CompoundTag> qs = open(id);
        StringBuilder sb = new StringBuilder();
        long day = level.getDayTime() / 24000L;
        for (CompoundTag q : qs) {
            CompoundTag mine = q.getCompound("ballots").getCompound(f.getUUID().toString());
            String when = q.getLong("vote") == day ? "today" : "on day " + (q.getLong("vote") + 1);
            if (!mine.isEmpty()) {
                sb.append(" I voted ").append(mine.getBoolean("aye") ? "aye" : "nay").append(" on ").append(q.getString("title"))
                    .append(": ").append(mine.getString("why")).append('.');
            } else {
                Judged j = judge(level, f, q);
                sb.append(" We vote ").append(when).append(" on ").append(q.getString("title")).append(". I'm minded to say ")
                    .append(j.aye() ? "aye" : "nay").append(": ").append(j.why()).append('.');
            }
        }
        if (qs.isEmpty() && BigWorks.underWay(id)) {
            CompoundTag w = BigWorks.current(id);
            if (w != null) sb.append(" We're building ").append(w.getString("title")).append(", all of us together.");
        }
        return sb.toString();
    }

    /** A line of gossip about the vote (Smalltalk): {question, answer, reply}, or null. */
    @Nullable
    public static String[] gossip(VillageFolkEntity a, VillageFolkEntity b) {
        UUID id = a.ownerId();
        if (id == null || !(a.level() instanceof ServerLevel level)) return null;
        List<CompoundTag> qs = open(id);
        if (qs.isEmpty()) return null;
        CompoundTag q = qs.get(0);
        Judged mine = judge(level, a, q), theirs = judge(level, b, q);
        boolean refuge = REFUGE.equals(q.getString("kind"));
        BigWorks.Work kind = BigWorks.Work.named(q.getString("subject"));
        String what = refuge ? "the folk at the edge of town" : kind == null ? "the vote" : kind.the;
        String open = refuge ? "Will you vote to take in " + what + "?" : "Which way are you voting on " + what + "?";
        String answer = (theirs.aye() ? "Aye. " : "Nay. ") + Elections.capital(theirs.why()) + ".";
        String reply = mine.aye() == theirs.aye() ? "Same here." : "Not me. " + (mine.aye() ? "Aye: " : "Nay: ") + mine.why() + ".";
        return new String[]{ open, answer, reply };
    }

    // ------------------------------------------------------------------ the board, the books, the card, its spirits

    /** The board's lines: what is put to the town and when, the last result, the work under way, the newcomers. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (CompoundTag q : open(village)) {
            boolean today = q.getLong("vote") == day;
            int voted = q.getCompound("ballots").getAllKeys().size(), of = voters(village).size();
            if (REFUGE.equals(q.getString("kind"))) {
                out.add("RG|" + capital(q.getString("title")) + (q.getString("title").startsWith("a family") ? ", ask" : ", asks") + " to settle."
                    + (today ? " VOTE TODAY at the board: " + voted + " of " + of
                    + " have voted." : " The town votes on day " + (q.getLong("vote") + 1) + "."));
            } else {
                out.add("RG|" + (today ? "VOTE TODAY at the board: " : "Vote on day " + (q.getLong("vote") + 1) + ": ") + q.getString("title")
                    + (q.getString("by").isEmpty() ? "" : ", put by " + q.getString("by")) + "." + (today ? " " + voted + " of " + of + " have voted." : ""));
                out.add("RN|It costs " + q.getString("cost") + " from the stores; the labour, " + q.getString("labour") + ". It brings "
                    + q.getString("brings") + ".");
            }
        }
        for (Tag t : CivicRecord.list(CivicRecord.town(village), "votesPast")) {
            if (!(t instanceof CompoundTag p) || day - p.getLong("day") > 2) continue;
            out.add("RM|The town voted on " + p.getString("title") + ": aye " + p.getInt("ayes") + ", nay " + p.getInt("nays") + " — "
                + (p.getBoolean("carried") ? "carried." : "lost."));
        }
        out.addAll(BigWorks.board(level, village));
        out.addAll(Newcomers.board(level, village));
        return out;
    }

    /** The town's books (the News page): the questions, the votes past, the works, the newcomers. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (CompoundTag q : open(village)) {
            int ayes = 0, nays = 0;
            CompoundTag ballots = q.getCompound("ballots");
            for (String k : ballots.getAllKeys()) if (ballots.getCompound(k).getBoolean("aye")) ayes++; else nays++;
            out.add("To be voted on day " + (q.getLong("vote") + 1) + ": " + q.getString("title") + " (so far aye " + ayes + ", nay " + nays + ")"
                + (WORKS.equals(q.getString("kind")) ? "; cost " + q.getString("cost") : "") + ".");
        }
        ListTag past = CivicRecord.list(CivicRecord.town(village), "votesPast");
        for (int i = past.size() - 1, k = 0; i >= 0 && k < 6; i--, k++) {
            if (!(past.get(i) instanceof CompoundTag p)) continue;
            out.add("Day " + (p.getLong("day") + 1) + ": " + p.getString("title") + " — aye " + p.getInt("ayes") + ", nay " + p.getInt("nays")
                + (p.getBoolean("carried") ? ", carried" : ", lost") + ".");
        }
        CompoundTag waits = CivicRecord.sub(CivicRecord.town(village), "waits");
        for (String k : waits.getAllKeys()) {
            BigWorks.Work w = BigWorks.Work.named(k);
            if (w != null && waits.getLong(k) > day) out.add(capital(w.a) + " waits a season: not to be put again before day " + (waits.getLong(k) + 1) + ".");
        }
        out.addAll(BigWorks.book(level, village));
        out.addAll(Newcomers.book(level, village));
        return out;
    }

    /** Its card: how it voted, the works it lent a hand to, and (a newcomer) where it came from. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.ownerId() == null || f.isShowcase()) return "";
        List<String> parts = new ArrayList<>();
        for (CompoundTag q : open(f.ownerId())) {
            CompoundTag mine = q.getCompound("ballots").getCompound(f.getUUID().toString());
            if (!mine.isEmpty()) parts.add("voted " + (mine.getBoolean("aye") ? "aye" : "nay") + " on " + q.getString("title"));
        }
        String works = BigWorks.cardLine(f);
        if (!works.isEmpty()) parts.add(works);
        String came = Newcomers.cardLine(f);
        if (!came.isEmpty()) parts.add(came);
        return String.join("; ", parts);
    }

    /** Its spirits (VillageFolkEntity.refreshMood): proud of the work it built; a newcomer's gratitude, or its quarrel. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        m = BigWorks.mood(f, day, m, why);
        return Newcomers.mood(f, day, m, why);
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        return why.equals("builtit") ? BigWorks.moodWords(f) : Newcomers.moodWords(f, why);
    }

    // ------------------------------------------------------------------ the commands

    /**
     * "/village referendum": what is put to the nearest town, the votes past and the work under way. A citizen votes
     * with "/village referendum vote aye|nay". For an operator: "call [work]" puts a great work to the town (the vote
     * today), "count" counts what is due now, "works" sets the works day going now, "open" finishes the work and opens
     * it, "stage" builds a river and a town's bridge over it beside the town for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("referendum")
            .executes(ctx -> say(ctx, near -> String.join("\n", book(level(ctx), near.id()))))
            .then(Commands.literal("vote")
                .then(Commands.argument("say", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    String w = StringArgumentType.getString(ctx, "say").toLowerCase(Locale.ROOT);
                    boolean aye = w.startsWith("a") || w.startsWith("y") || w.startsWith("f");
                    return say(ctx, near -> playerVote(level(ctx), near, p, aye));
                })))
            .then(Commands.literal("call").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> callNow(level(ctx), near, null)))
                .then(Commands.argument("work", StringArgumentType.word()).executes(ctx ->
                    say(ctx, near -> callNow(level(ctx), near, BigWorks.Work.named(StringArgumentType.getString(ctx, "work").toUpperCase(Locale.ROOT)))))))
            .then(Commands.literal("count").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> countNow(level(ctx), near))))
            .then(Commands.literal("works").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> "WORKS " + BigWorks.helpersForTests(level(ctx), near.id()).size() + " hands called; "
                    + BigWorks.stateForTests(near.id()))))
            .then(Commands.literal("open").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> "OPEN " + openNow(level(ctx), near))))
            .then(Commands.literal("finish").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> String.join("\n", CivicVotesStage.finish(level(ctx), near)))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, near -> String.join("\n", CivicVotesStage.works(level(ctx), near,
                    BlockPos.containing(ctx.getSource().getPosition()))))));
    }

    /** An operator's call: the work named (or the most wanted), put to the town with the vote today. */
    static String callNow(ServerLevel level, Villages.Village v, @Nullable BigWorks.Work want) {
        long day = level.getDayTime() / 24000L;
        for (BigWorks.Proposal p : BigWorks.proposals(level, v)) {
            if (want != null && p.kind() != want) continue;
            CompoundTag q = callWorks(level, v, p, day, day);
            return "CALLED " + q.getInt("id") + " " + p.title() + " | " + p.costWords() + " | " + p.labour();
        }
        return "NONE the land and the stores have no place for " + (want == null ? "a great work" : want.a) + " here";
    }

    /** An operator's count: every grown folk votes now as it stands, the count, and what it decided. */
    static String countNow(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        for (CompoundTag q : open(v.id())) {
            for (VillageFolkEntity f : voters(v.id())) cast(level, q, f, false);
            count(level, v, q, day);
            decide(level, v, q, day);
            sb.append("COUNTED ").append(q.getString("title")).append(": aye ").append(q.getInt("ayes")).append(", nay ").append(q.getInt("nays"))
                .append(q.getBoolean("carried") ? " carried" : " lost").append('\n');
        }
        return sb.length() == 0 ? "NONE nothing to count" : sb.toString().trim();
    }

    /** An operator's opening: whatever is left laid now out of the stores (no walk, no hands), the ribbon strung and cut. */
    static String openNow(ServerLevel level, Villages.Village v) {
        CompoundTag w = BigWorks.current(v.id());
        if (w == null) return "nothing under way";
        for (int i = 0; i < 4000 && BigWorks.setNext(level, v, w, null); i++) { }
        return BigWorks.finishAndOpenForTests(level, v);
    }

    static ServerLevel level(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getLevel();
    }

    static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Vec3 at = ctx.getSource().getPosition();
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(at), 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a great work put to the town now, the vote on the day given; returns the question's number, or -1. */
    public static int callForTests(ServerLevel level, Villages.Village v, BigWorks.Work kind, long voteDay) {
        long day = level.getDayTime() / 24000L;
        for (BigWorks.Proposal p : BigWorks.proposals(level, v)) {
            if (p.kind() != kind) continue;
            return callWorks(level, v, p, day, voteDay).getInt("id");
        }
        return -1;
    }

    /** Tests: who put this question to the town (its leader, or the council's first), or null. */
    @Nullable
    public static UUID callerForTests(UUID village, int id) {
        CompoundTag q = question(village, id);
        if (q == null || q.getString("byId").isEmpty()) return null;
        try {
            return UUID.fromString(q.getString("byId"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tests: how this folk would vote on the question ("aye|why" or "nay|why"). */
    public static String judgeForTests(ServerLevel level, VillageFolkEntity f, UUID village, int id) {
        CompoundTag q = question(village, id);
        if (q == null) return "none";
        Judged j = judge(level, f, q);
        return (j.aye() ? "aye" : "nay") + "|" + j.why();
    }

    /** Tests: every grown folk of the town casts its vote now (not at the board). */
    public static void castAllForTests(ServerLevel level, Villages.Village v, int id) {
        CompoundTag q = question(v.id(), id);
        if (q == null) return;
        for (VillageFolkEntity f : voters(v.id())) cast(level, q, f, false);
    }

    /** Tests: the ballot this folk cast ({voted, aye, atTheBoard}). */
    public static boolean[] ballotForTests(UUID village, int id, UUID folk) {
        CompoundTag q = question(village, id);
        if (q == null) return new boolean[]{ false, false, false };
        CompoundTag b = q.getCompound("ballots").getCompound(folk.toString());
        return new boolean[]{ !b.isEmpty(), b.getBoolean("aye"), b.getBoolean("board") };
    }

    /** Tests: count it now and act on it; returns {ayes, nays, carried (1/0)}. */
    public static int[] countForTests(ServerLevel level, Villages.Village v, int id) {
        CompoundTag q = question(v.id(), id);
        if (q == null) return new int[]{ -1, -1, 0 };
        long day = level.getDayTime() / 24000L;
        count(level, v, q, day);
        decide(level, v, q, day);
        return new int[]{ q.getInt("ayes"), q.getInt("nays"), q.getBoolean("carried") ? 1 : 0 };
    }

    /** Tests: the day until which a work voted down waits (0: it does not). */
    public static long waitsForTests(UUID village, BigWorks.Work kind) {
        return waitsUntil(village, kind);
    }

    /** Tests: the town's look at whether to call a vote, today (whatever it found, the question's title or ""). */
    public static String considerForTests(ServerLevel level, Villages.Village v) {
        CivicRecord.town(v.id()).remove("considered");
        int before = list(v.id()).size();
        consider(level, v);
        ListTag all = list(v.id());
        return all.size() > before && all.get(all.size() - 1) instanceof CompoundTag q ? q.getString("title") : "";
    }

    /** Tests: the questions now put to the town ("id kind title"). */
    public static List<String> openForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (CompoundTag q : open(village)) out.add(q.getInt("id") + " " + q.getString("kind") + " " + q.getString("title"));
        return out;
    }
}
