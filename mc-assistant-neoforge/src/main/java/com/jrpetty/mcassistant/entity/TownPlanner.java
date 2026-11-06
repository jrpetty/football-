package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

/**
 * [roles-e] The town planner: a post, not a trade, at the hall of a town of twelve or more. Its holder (reckoned each day
 * from the best suited, a curious and hard-working head; kept while the interview's choice is fit: InterviewPosts, kind
 * PLANNER) looks each morning after the dawn bell at the next building the town wants (Villages.nextProject); when it
 * is a new one, it goes to the hall's table a while to draw it up (PostStints) and sets it out: a line in the chronicle
 * with why the town wants it (Villages.whyBuild), and a word to whoever is about. Asked "What's being built next?" (Town,
 * Council) it says what the builders are on, what comes next and why, and what follows; anybody else points to it.
 * Paid an office's allowance (Offices); dressed in a waistcoat and felt hat (Weave).
 *
 * <p>Cost: once a second a look at each loaded town's hour (the morning only), and the holder at most every five seconds.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class TownPlanner {

    private TownPlanner() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The post's key in the interviews' books, and its stints'. */
    static final String POST = "planner";
    /** The smallest town that keeps a planner. */
    static final int FROM_FOLK = 12;
    /** The morning at the hall's table: from the dawn bell till mid-morning; a stint this long, so long at the table. */
    static final long MORNING_FROM = 1000L, MORNING_TILL = 5000L, STINT = 1200L, STUCK = 900L;
    static final int AT_TABLE = 20;
    private static final String NOTED = "planner.next";
    private static final String DAY = "planner.day";
    private static final String[] HALLS = { "townhall", "hall" };

    private static final Map<UUID, Object[]> HOLDER = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos[]> DESK = new ConcurrentHashMap<>();
    /** The building the planner is drawing up this morning, by town (worked out once a morning). */
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HOLDER.clear();
        DESK.clear();
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        resetForTests();
    }

    // ------------------------------------------------------------------ who

    /** Does the town keep a planner: a hall standing, and twelve folk or more? */
    public static boolean stands(@Nullable UUID id) {
        return id != null && hall(id) != null && Villages.headcount(id) >= FROM_FOLK;
    }

    @Nullable
    private static Ledger.Building hall(UUID id) {
        for (String h : HALLS) {
            Ledger.Building b = Villages.builtStructure(id, h);
            if (b != null) return b;
        }
        return null;
    }

    /** Free for the post: grown and steady, at home, not the elder (who leads, and the planner advises it), nor the watch. */
    static boolean fit(VillageFolkEntity f, UUID id) {
        if (!f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired() || f.isElder() || !id.equals(f.ownerId())) return false;
        if (f.expedition() != null || f.ageYears() < 25) return false;
        StationTask t = f.stationTask();
        return t != StationTask.GUARD && t != StationTask.NETHER && t != StationTask.CAVE && t != StationTask.SCOUT
            && t != StationTask.EMERALD && t != StationTask.FERRY && t != StationTask.DIVER && t != StationTask.HEAL;
    }

    /** How well suited: a curious, hard-working, older head, and a hand that has built. */
    static int score(VillageFolkEntity f) {
        int s = f.ageYears() / 3 + f.veteranLevel() / 2;
        if (f.life().has(Social.Trait.CURIOUS)) s += 15;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 10;
        if (f.stationTask() == StationTask.SHOP || f.stationTask() == StationTask.SMITH) s += 5;
        if (f.life().has(Social.Trait.GRUMPY)) s -= 10;
        return s;
    }

    /** The town's planner: the interview's choice while it is fit, else the best suited; null with no hall. */
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

    static void forget(UUID id) {
        HOLDER.remove(id);
    }

    public static boolean is(VillageFolkEntity f) {
        return f.ownerId() != null && f == holder(f.ownerId());
    }

    // ------------------------------------------------------------------ the morning at the hall

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 17) return;
        com.jrpetty.mcassistant.Guard.run("planner", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                long t = level.getDayTime() % 24000L;
                if (t < MORNING_FROM || t >= MORNING_TILL) continue;
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    morning(level, v);
                }
            }
        });
    }

    /**
     * The planner's morning: once a day it looks at what the town wants next, and only if that is a new building does it
     * go to the hall's table to draw it up, a while, and set it out; on other mornings it stays at its own trade.
     */
    static void morning(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (Long.toString(day).equals(Ledger.note(id, DAY)) || Raids.underAlarm(id) || !stands(id)) return;
        String next = PENDING.computeIfAbsent(id, k -> String.valueOf(Villages.nextProject(id)));
        if (next.equals("null") || next.equals(Ledger.note(id, NOTED))) {
            Ledger.note(id, DAY, Long.toString(day));                         // nothing new to set out today
            PENDING.remove(id);
            return;
        }
        VillageFolkEntity h = holder(id);
        BlockPos desk = h == null ? null : desk(level, id);
        if (desk == null) return;
        boolean stuck = PostStints.stuck(h, STUCK);                           // the table out of reach: set out where it stands
        if (!stuck) {
            if (!PostStints.call(h, POST, desk, "drawing up the plans for " + Villages.spoken(next), STINT, 0L)) return;
            int sec = PostStints.worked(h);
            if (sec % 6 == 1) level.playSound(null, desk, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.6F, 1.0F);
            if (sec < AT_TABLE) return;
        }
        PostStints.end(h, 0L);
        Ledger.note(id, DAY, Long.toString(day));
        PENDING.remove(id);
        announce(level, v, h, day);
    }

    /** The next building set out, if it is a new one: the chronicle, and a word to whoever is about. True if it was. */
    static boolean announce(ServerLevel level, Villages.Village v, VillageFolkEntity h, long day) {
        UUID id = v.id();
        String next = Villages.nextProject(id);
        String noted = Ledger.note(id, NOTED);
        if (next == null || next.equals(noted)) return false;
        Ledger.note(id, NOTED, next);
        String why = Villages.whyBuild(id, next);
        Villages.tell(id, day, h.displayNameCap() + " the town planner set out the plans for " + Villages.spoken(next) + ": " + why);
        FolkTalk.speak(h, FolkTalk.pick(h.getRandom(), "Next we build " + Villages.spoken(next) + ". The plans are on the table.",
            "I've drawn up " + Villages.spoken(next) + ". The builders start as soon as the stores run to it.",
            "The town wants " + Villages.spoken(next) + ", and it shall have it."));
        LOG.info("[MCA-PLANNER] {} set out {} for {}", h.displayNameCap(), next, Villages.name(id));
        return true;
    }

    /** The hall's table (its crafting table, else a lectern), or the hall's own door step; null with no hall in sight. */
    @Nullable
    static BlockPos desk(ServerLevel level, UUID id) {
        Ledger.Building b = hall(id);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        BlockPos[] kept = DESK.get(id);
        if (kept != null && kept[0].equals(b.anchor())) return kept[1];
        BlockPos got = null;
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CRAFTING_TABLE || got == null && p.part() == BuildGoal.Part.LECTERN) got = p.pos().immutable();
            if (p.part() == BuildGoal.Part.CRAFTING_TABLE) break;
        }
        if (got == null) got = b.anchor();
        DESK.put(id, new BlockPos[]{ b.anchor(), got });
        return got;
    }

    // ------------------------------------------------------------------ the talk and the books

    /** What the builders are on just now ("the bakery"), or null. */
    @Nullable
    static String buildingNow(UUID id) {
        for (AssistantEntity x : Villages.folkOf(id)) {
            Job j = x.peekJob();
            if (j != null && j.type() == Job.Type.BUILD && j.arg() != null) return j.arg().split("\\|", 2)[0];
        }
        return null;
    }

    /** "What's being built next?": what the builders are on, what comes next and why, and what follows. */
    static String talk(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "I couldn't tell you.";
        String now = buildingNow(id), next = Villages.nextProject(id);
        VillageFolkEntity h = holder(id);
        if (h != f) {
            String brief = next == null ? "" : " Last I heard it's " + Villages.spoken(next) + " next.";
            return h == null ? "Nobody draws up plans here: the council says what goes up." + brief
                : "Ask " + h.displayNameCap() + ", the town planner: it has the plans at the hall." + brief;
        }
        StringBuilder sb = new StringBuilder();
        if (now != null) sb.append("The builders are on ").append(Villages.spoken(now)).append(" just now. ");
        if (next == null) {
            sb.append("Nothing new to set out: the town's gathering what its age asks for.");
            return sb.toString().trim();
        }
        if (!next.equals(now)) sb.append("Next is ").append(Villages.spoken(next)).append(": ").append(Villages.whyBuild(id, next)).append(".");
        List<String> after = new ArrayList<>();
        for (String p : Villages.projectsWanted(id)) {
            if (p.equals(next) || p.equals(now) || after.contains(Villages.spoken(p))) continue;
            after.add(Villages.spoken(p));
            if (after.size() >= 2) break;
        }
        if (!after.isEmpty()) sb.append(" After that, ").append(String.join(" and ", after)).append(".");
        return sb.toString().trim();
    }

    /** Typed words that ask what is to be built. */
    static boolean meant(String lower) {
        return lower.contains("built next") || lower.contains("build next") || lower.contains("building next")
            || lower.contains("next project") || lower.contains("being built") || lower.contains("town plan");
    }

    /** "/village roads": the planner and what it set out last. */
    static List<String> lines(UUID id) {
        List<String> out = new ArrayList<>();
        VillageFolkEntity h = holder(id);
        String noted = Ledger.note(id, NOTED);
        out.add("Town planner: " + (h == null ? (stands(id) ? "none yet" : "not kept (a hall and twelve folk)") : h.displayNameCap()
            + " (" + h.stationTask().label + ")") + (noted == null || noted.isEmpty() ? "" : "; last set out " + Villages.spoken(noted)));
        return out;
    }

    static String cardLine(VillageFolkEntity f) {
        if (!is(f)) return "";
        String noted = Ledger.note(f.ownerId(), NOTED);
        return "the town planner, at the hall" + (noted == null || noted.isEmpty() ? "" : "; last set out " + Villages.spoken(noted));
    }

    // ------------------------------------------------------------------ for the tests

    public static boolean announceForTests(ServerLevel level, Villages.Village v) {
        VillageFolkEntity h = holder(v.id());
        return h != null && announce(level, v, h, level.getDayTime() / 24000L);
    }

    public static void morningForTests(ServerLevel level, Villages.Village v) {
        morning(level, v);
    }

    public static String talkForTests(VillageFolkEntity f) {
        return talk(f);
    }

    public static void forgetForTests(UUID id) {
        HOLDER.remove(id);
        DESK.remove(id);
        PENDING.remove(id);
    }
}
