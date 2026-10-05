package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a village is doing, all told: whether there is enough to eat, a bed for everyone,
 * whether its people are cheerful, whether it is safe, what it has to enjoy, whether the
 * wages are paid. A score out of a hundred:
 * <ul>
 * <li><b>thriving</b> (80+) and <b>content</b> (60+) villages work faster, and their
 *     people raise children readily;</li>
 * <li><b>getting by</b> (40+) is a village as it comes;</li>
 * <li><b>unhappy</b> (25+) and <b>miserable</b> villages go slow, their people grumble,
 *     and children are rare — never impossible: a village that is fed at all still grows,
 *     slowly;</li>
 * <li>a village miserable three days running starts to lose people: one at a time, every
 *     other day, to the happiest village nearby (or off into the world), never below eight.</li>
 * </ul>
 */
public final class Contentment {

    private Contentment() {}

    /** The score and what goes into it, with the reasons in words. */
    public record View(int score, String word, int food, int homes, int mood, int safety, int amenities, int wages,
                       List<String> good, List<String> bad) {}

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AT = new ConcurrentHashMap<>();
    /** The last day somebody of the village died or was lost. */
    private static final Map<UUID, Long> LOSS = new ConcurrentHashMap<>();
    /** Days running a village has been miserable, and the last day that was counted. */
    private static final Map<UUID, Integer> MISERY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COUNTED = new ConcurrentHashMap<>();
    /** The last day somebody left. */
    private static final Map<UUID, Long> LEFT = new ConcurrentHashMap<>();

    /** Below this a village loses no more people. */
    static final int KEEP_AT_LEAST = 8;

    public static void resetForTests() {
        VIEWS.clear();
        AT.clear();
        LOSS.clear();
        MISERY.clear();
        COUNTED.clear();
        LEFT.clear();
    }

    public static String word(int score) {
        return score >= 80 ? "thriving" : score >= 60 ? "content" : score >= 40 ? "getting by" : score >= 25 ? "unhappy" : "miserable";
    }

    /** The village's contentment now (worked out at most every half minute). */
    public static View of(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        View v = VIEWS.get(village);
        if (v != null && now - AT.getOrDefault(village, -100000L) < 600L) return v;
        v = compute(level, village);
        VIEWS.put(village, v);
        AT.put(village, now);
        return v;
    }

    /** The last score worked out, without a level to hand (55 — getting by — if none yet). */
    public static int score(@Nullable UUID village) {
        View v = village == null ? null : VIEWS.get(village);
        return v == null ? 55 : v.score();
    }

    /** How much faster (or slower) a village's people work, in percent. */
    public static int workPercent(@Nullable UUID village) {
        int s = score(village);
        return s >= 80 ? 10 : s >= 60 ? 5 : s < 25 ? -10 : s < 40 ? -5 : 0;
    }

    /** Somebody of the village died today. */
    public static void loss(@Nullable UUID village, long day) {
        if (village != null) LOSS.put(village, day);
    }

    static View compute(ServerLevel level, UUID id) {
        List<String> good = new ArrayList<>(), bad = new ArrayList<>();
        Villages.Village v = Villages.get(id);
        List<AssistantEntity> folk = Villages.folkOf(id);
        int n = Math.max(1, folk.size());
        int head = Math.max(1, Villages.headcount(id));
        long day = level.getDayTime() / 24000L;
        // Food: the larder against what the village would want put by for a child.
        int food = v == null ? 0 : Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        int want = Math.max(1, VillageMath.larderForBirth(head));
        int foodPts = (int) Math.min(25, Math.round(25.0 * food / want));
        if (foodPts >= 20) good.add("plenty to eat"); else if (foodPts < 10) bad.add("not enough to eat");
        // Homes: a bed of one's own.
        int bedded = 0;
        for (AssistantEntity a : folk) if (a.bedPos() != null) bedded++;
        int homesPts = (int) Math.round(20.0 * bedded / n);
        if (head > Villages.housing(id)) homesPts = Math.max(0, homesPts - 5);
        if (homesPts >= 18) good.add("a bed for everyone"); else if (homesPts < 12) bad.add("not everyone has a bed");
        // Mood: what its people feel, on average.
        int moodSum = 0, moods = 0;
        for (AssistantEntity a : folk) {
            if (a instanceof VillageFolkEntity f) { moodSum += f.persona().mood(); moods++; }
        }
        int avgMood = moods == 0 ? 55 : moodSum / moods;
        int moodPts = Math.round(avgMood / 4.0F);
        if (avgMood >= 70) good.add("everyone's cheerful"); else if (avgMood < 40) bad.add("folk are low");
        // Safety.
        int safety = 10;
        if (LOSS.getOrDefault(id, -10L) >= day - 1) { safety -= 6; bad.add("we lost somebody"); }
        if (Raids.underAlarm(id)) safety -= 4;
        if (Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal() && !Villages.hasBuilt(id, "fortify")) safety -= 2;
        else if (Villages.hasBuilt(id, "fortify")) good.add("safe behind the wall");
        safety += Diplomacy.safety(id, good, bad);
        safety = Math.max(0, Math.min(10, safety));
        // Things to enjoy.
        int amenities = 0;
        for (String s : new String[]{ "well", "market", "cafe", "tavern", "chapel" }) {
            if (Villages.hasBuilt(id, s)) amenities += 2;
        }
        if (amenities >= 6) good.add("plenty to do of an evening");
        // Wages.
        // What the last payday actually paid: the morning's business ran every day whether there
        // was coin for the wages or not, and "the wages are paid" was said of a town paying 2%.
        int share = Ledger.paidOn(id) >= day - 1 ? Market.lastShare(id) : -1;
        int wages = share >= 90 ? 5 : share >= 50 ? 3 : share >= 20 ? 1 : 0;
        if (share >= 90) good.add("the wages are paid");
        else if (share >= 0) bad.add(share >= 50 ? "the wages are paid short" : "the wages are hardly paid");
        // A day of rest kept this week.
        int rest = RestDay.keptThisWeek(id, day) ? 5 : 0;
        if (rest > 0) good.add("a day of rest");
        int score = Math.max(0, Math.min(100, foodPts + homesPts + moodPts + safety + amenities + wages + rest));
        return new View(score, word(score), foodPts, homesPts, moodPts, safety, amenities, wages, good, bad);
    }

    /** A line for a player: the score, its word and why. */
    public static String line(ServerLevel level, UUID village) {
        View v = of(level, village);
        StringBuilder sb = new StringBuilder();
        sb.append(v.score()).append(" (").append(v.word()).append(")");
        if (!v.good().isEmpty()) sb.append(" — ").append(String.join(", ", v.good()));
        if (!v.bad().isEmpty()) sb.append(v.good().isEmpty() ? " — " : "; but ").append(String.join(", ", v.bad()));
        return sb.toString();
    }

    /** What an unhappy folk grumbles about, or null if there is nothing to grumble at. */
    @Nullable
    public static String grumble(ServerLevel level, UUID village, net.minecraft.util.RandomSource r) {
        View v = of(level, village);
        if (v.score() >= 40 || v.bad().isEmpty()) return null;
        String why = v.bad().get(r.nextInt(v.bad().size()));
        return switch (why) {
            case "not enough to eat" -> FolkTalk.pick(r, "My stomach's been growling all day.", "There's never enough bread in this place.");
            case "not everyone has a bed" -> FolkTalk.pick(r, "Another night on the ground. My back!", "We need more houses, and soon.");
            case "folk are low" -> FolkTalk.pick(r, "Nobody smiles round here any more.", "What's the point of it all?");
            case "we lost somebody" -> FolkTalk.pick(r, "I can't stop thinking about who we lost.", "It's not the same without them.");
            default -> "Things aren't right in this village.";
        };
    }

    // ------------------------------------------------------------------ moving away

    /**
     * Once a day: is the village miserable, and has it been for long enough that somebody
     * gives up on it? Returns who left, or null.
     */
    @Nullable
    public static String daily(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (COUNTED.getOrDefault(id, -10L) >= day) return null;
        COUNTED.put(id, day);
        View view = of(level, id);
        int days = view.score() < 25 ? MISERY.merge(id, 1, Integer::sum) : 0;
        if (days == 0) MISERY.remove(id);
        if (days < 3) return null;
        if (Villages.headcount(id) <= KEEP_AT_LEAST) return null;
        if (LEFT.getOrDefault(id, -10L) >= day - 1) return null;
        VillageFolkEntity who = leaver(id);
        if (who == null) return null;
        LEFT.put(id, day);
        String name = who.displayNameCap();
        String why = view.bad().isEmpty() ? "there was no living to be had" : view.bad().get(0);
        Villages.Village to = happiest(v);
        if (to != null) {
            who.leaveFor(level, to);
            Villages.tell(id, day, name + " left for " + Villages.name(to.id()) + ", because " + why);
            Villages.tell(to.id(), day, name + " came from " + Villages.name(id) + " to live here");
        } else {
            Villages.tell(id, day, name + " left to find a better life, because " + why);
            who.walkOut();
        }
        Raids.tellNear(level, v.centre(), 160, Component.literal(name + " has left " + Villages.name(id) + ": " + why + ".")
            .withStyle(ChatFormatting.GOLD), false);
        return name;
    }

    /** The one with least to keep it: no partner, not the elder, not on the watch, fewest friends. */
    @Nullable
    static VillageFolkEntity leaver(UUID village) {
        VillageFolkEntity best = null;
        int fewest = Integer.MAX_VALUE;
        java.util.UUID elder = Villages.elder(village);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase()) continue;
            if (f.life().partner() != null || f.getUUID().equals(elder)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            int ties = f.life().friends().size();
            if (ties < fewest) { fewest = ties; best = f; }
        }
        return best;
    }

    /** The happiest other village with room and its heart loaded, or null. */
    @Nullable
    static Villages.Village happiest(Villages.Village from) {
        Villages.Village best = null;
        int bestScore = 44;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(from.id()) || !o.dim().equals(from.dim())) continue;
            if (Villages.headcount(o.id()) >= Villages.housing(o.id())) continue;
            int s = score(o.id());
            if (s > bestScore) { bestScore = s; best = o; }
        }
        return best;
    }

    /** Where to put a newcomer down at a village's heart. */
    static BlockPos arrival(ServerLevel level, Villages.Village to, net.minecraft.util.RandomSource r) {
        int x = to.centre().getX() + r.nextInt(7) - 3, z = to.centre().getZ() + r.nextInt(7) - 3;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }
}
