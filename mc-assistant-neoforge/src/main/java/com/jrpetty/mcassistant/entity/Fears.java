package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.vehicle.Boat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [individual] What a folk is afraid of, what the fear makes it do, and how it gets over it.
 *
 * <ul>
 * <li><b>The dark.</b> Home before dusk: it knocks off a little early and is indoors by its bed before the light goes,
 *     every evening; it is never a miner nor one of the cave team.</li>
 * <li><b>Deep water.</b> It will not be a fisher or the ferryman (so never one of the fleet's crews either), and its
 *     favourite place is never the quay.</li>
 * <li><b>Heights.</b> Nobody sends it up the bell tower to ring the town bell, and its favourite place is never the hill.</li>
 * <li><b>The Nether.</b> It never goes through the gateway with a Nether party, and never dreams of it.</li>
 * <li><b>Crowds.</b> It stays away from the feasts, the celebrations, the festivals and Founding Day (the morning
 *     assembly and a funeral it still goes to).</li>
 * <li><b>Monsters.</b> Never a guard, a hunter or one of the cave team; and at the first sight of one near it, it is
 *     off home.</li>
 * </ul>
 * A trade the town wants that it is too frightened for goes to somebody else ({@link #steer}, {@link #shuns}).
 *
 * <p><b>Overcome by living through it.</b> Every day it comes through the thing it fears unharmed (out after dark, in
 * a boat, high up, at a gathering, through a raid, a friend home safe from the Nether) its courage grows; on the
 * fifth such day the fear is gone, and the chronicle says so.
 */
public final class Fears {

    private Fears() {}

    public enum Fear {
        DARK("the dark", "afraid of the dark"),
        DEEP_WATER("deep water", "afraid of deep water"),
        HEIGHTS("heights", "afraid of heights"),
        NETHER("the Nether", "afraid of the Nether"),
        CROWDS("crowds", "uneasy in a crowd"),
        MONSTERS("monsters", "afraid of monsters");

        public final String word, said;

        Fear(String word, String said) {
            this.word = word;
            this.said = said;
        }
    }

    /** Brave days it takes to get over a fear. */
    public static final int COURAGE = 5;
    /** The dark: home from this hour (a little before dusk) to the morning. */
    static final long HOME_FROM = 11300L, HOME_TILL = 23400L;

    private static final Map<UUID, Integer> HELD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SCARED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SAID = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FLEEING = new ConcurrentHashMap<>();
    private static final Map<String, Long> BRAVE_DAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HELD.clear();
        SCARED.clear();
        SAID.clear();
        FLEEING.clear();
        BRAVE_DAY.clear();
    }

    public static boolean dreads(VillageFolkEntity f, Fear fear) {
        return f.individual().fears.contains(fear);
    }

    // ------------------------------------------------------------------ rolled

    static void roll(VillageFolkEntity f, Individual.Self s, RandomSource r, boolean child) {
        s.fears.clear();
        int n = child ? (r.nextInt(3) == 0 ? 2 : 1) : r.nextInt(10) < 4 ? 0 : r.nextInt(10) < 8 ? 1 : 2;
        double[] w = {2, 2, 2, 1.6, 1.2 + (f.life().has(Social.Trait.SHY) ? 3 : 0), 1.4};
        if (child) w[Fear.DARK.ordinal()] += 4;
        if (f.persona().rolled() && f.persona().quirk().equals("is afraid of the dark")) s.fears.add(Fear.DARK);
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (double d : w) sum += d;
            double x = r.nextDouble() * sum;
            for (int k = 0; k < w.length; k++) {
                x -= w[k];
                if (x < 0) {
                    s.fears.add(Fear.values()[k]);
                    w[k] = 0;
                    break;
                }
            }
        }
        // A grown folk already at a trade has long since got used to what that trade asks of it.
        StationTask t = f.stationTask();
        if (!child && t != StationTask.NONE) s.fears.removeIf(fear -> shuns(fear, t));
    }

    // ------------------------------------------------------------------ the trades it will not take

    /** Is this a trade a folk afraid of this would not take? */
    static boolean shuns(Fear fear, StationTask t) {
        return switch (fear) {
            case DARK -> t == StationTask.MINE || t == StationTask.CAVE;
            case DEEP_WATER -> t == StationTask.FISH || t == StationTask.FERRY;
            case MONSTERS -> t == StationTask.GUARD || t == StationTask.HUNT || t == StationTask.CAVE;
            default -> false;
        };
    }

    /** Would this folk refuse this trade, for a fear of its own? */
    public static boolean shuns(VillageFolkEntity f, StationTask t) {
        for (Fear fear : f.individual().fears) if (shuns(fear, t)) return true;
        return false;
    }

    /** The trade it takes up, where the town wants {@code wanted}: that one, unless it is too frightened for it, when it
     *  takes the next the town is short of that it is not (the fields, failing all else). (takeUpATrade) */
    public static StationTask steer(VillageFolkEntity f, StationTask wanted) {
        if (wanted == null || !shuns(f, wanted)) return wanted;
        UUID village = f.ownerId();
        if (village != null) {
            for (StationTask t : Villages.shortOfHands(village)) if (t != wanted && t != StationTask.NONE && !shuns(f, t)) return t;
        }
        return StationTask.FARM;
    }

    // ------------------------------------------------------------------ what it does

    /** From its tick: home before dusk, or off home at the sight of a monster. True while it is. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        if (s.fears.isEmpty() || f.isBaby() || f.isSleeping() || f.ownerId() == null || Raids.underAlarm(f.ownerId())) return release(f);
        if (s.fears.contains(Fear.MONSTERS) && flee(f, level)) return true;
        if (s.fears.contains(Fear.DARK) && homeBeforeDusk(f, level)) return true;
        return release(f);
    }

    static boolean busy(VillageFolkEntity f) {
        Integer h = HELD.get(f.getUUID());
        return h != null && f.tickCount - h >= 0 && f.tickCount - h <= 8;
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        return false;
    }

    /**
     * The dark: from a little before dusk it is on its way home, and stays in till its bedtime puts it to bed.
     * Not while the town has it at a gathering, the watch's business or its family's: those it does in company.
     */
    public static boolean homeBeforeDusk(VillageFolkEntity f, ServerLevel level) {
        long tod = level.getDayTime() % 24000L;
        if (tod < HOME_FROM || tod >= HOME_TILL) return false;
        BlockPos bed = f.bedPos();
        if (bed == null || Assemblies.attending(f) || TownJobs.busy(f) || Families.busy(f) || f.getTarget() != null) return false;
        if (f.peekJob() != null && tod < 12000L) return false;     // the job in its hands finished first, then home
        if (f.stationTask() == StationTask.GUARD) return false;
        long day = level.getDayTime() / 24000L;
        boolean home = f.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) <= 2.5 * 2.5;
        if (home && tod >= f.bedtimeTick() - 200L) return false;    // at home at bedtime: bed, as ever
        HELD.put(f.getUUID(), f.tickCount);
        f.hobbyNow = home ? "indoors before dark" : "hurrying home before dark";
        f.lastLeisureTick = f.tickCount;
        if (!home) {
            if (f.getNavigation().isDone() || f.tickCount % 80 == 0) f.walkTo(bed, 1.0D);
            if (SAID.getOrDefault(f.getUUID(), -1L) != day) {
                SAID.put(f.getUUID(), day);
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Getting dark — I'm off home.", "Not out after dark, not me.",
                    "The light's going. Home, home."));
            }
        } else {
            f.getNavigation().stop();
        }
        return true;
    }

    /** Monsters: one within a dozen blocks, and it is off home (or away from it), for a while. */
    private static boolean flee(VillageFolkEntity f, ServerLevel level) {
        Integer until = FLEEING.get(f.getUUID());
        if (until != null && f.tickCount < until) {
            HELD.put(f.getUUID(), f.tickCount);
            return true;
        }
        if (f.tickCount % 20 != 5 || f.stationTask() == StationTask.GUARD) return false;
        Monster m = null;
        double best = 12.0 * 12.0;
        for (Monster o : level.getEntitiesOfClass(Monster.class, f.getBoundingBox().inflate(12.0), Monster::isAlive)) {
            double d = o.distanceToSqr(f);
            if (d < best) { best = d; m = o; }
        }
        if (m == null) return false;
        f.clearQueue();                                              // its work waits: it is off
        BlockPos bed = f.bedPos();
        if (bed != null && bed.distSqr(f.blockPosition()) < 64 * 64) f.walkTo(bed, 1.2D);
        else {
            double dx = f.getX() - m.getX(), dz = f.getZ() - m.getZ(), len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            BlockPos away = f.surfaceAt((int) (f.getX() + dx / len * 10), (int) (f.getZ() + dz / len * 10));
            if (away != null) f.walkTo(away, 1.2D);
        }
        long day = level.getDayTime() / 24000L;
        SCARED.put(f.getUUID(), day);
        FLEEING.put(f.getUUID(), f.tickCount + 160);
        HELD.put(f.getUUID(), f.tickCount);
        f.hobbyNow = "running from a monster";
        f.lastLeisureTick = f.tickCount;
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A monster! I'm off!", "No, no, no — not for me!", "Help! Somebody fetch the watch!"));
        return true;
    }

    /** Assemblies.invited: a folk uneasy in a crowd stays away from the big, merry gatherings. */
    public static boolean shunsCrowd(VillageFolkEntity f, String kind) {
        if (!dreads(f, Fear.CROWDS)) return false;
        return kind.equals("FEAST") || kind.equals("CELEBRATION") || kind.equals("FESTIVAL") || kind.equals("FOUNDING");
    }

    /** TownBell.appoint: nobody afraid of heights is sent up the bell tower. */
    public static double bellPenalty(VillageFolkEntity f, UUID village) {
        return dreads(f, Fear.HEIGHTS) && Villages.hasBuilt(village, "belltower") ? 500.0 : 0.0;
    }

    /** Nether.tick: never one afraid of it in the party. */
    public static boolean staysThisSide(VillageFolkEntity f) {
        return dreads(f, Fear.NETHER);
    }

    /** Frightened today: a little out of sorts for it. */
    static int mood(VillageFolkEntity f, long day) {
        Long d = SCARED.get(f.getUUID());
        return d != null && day - d <= 0 ? 5 : 0;
    }

    // ------------------------------------------------------------------ overcome

    /**
     * Once a day or so (Individual.tick): did it come through what it fears today? Out after dark awake, in a boat,
     * high above the town, at one of the town's gatherings, through a raid, or the town a Nether town with a friend
     * through the gateway and home: each a brave day.
     */
    static void daily(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        if (s.fears.isEmpty() || f.ownerId() == null || f.isSleeping()) return;
        long day = level.getDayTime() / 24000L;
        for (Fear fear : List.copyOf(s.fears)) {
            boolean brave = switch (fear) {
                case DARK -> level.isNight() && level.canSeeSky(f.blockPosition()) && f.getHealth() > f.getMaxHealth() * 0.5F;
                case DEEP_WATER -> f.getVehicle() instanceof Boat || Transport.aboard(f);
                case HEIGHTS -> f.villageCentre() != null && f.getY() >= f.villageCentre().getY() + 12;
                case CROWDS -> Assemblies.attending(f);
                case MONSTERS -> Raids.underAlarm(f.ownerId()) && f.getHealth() > f.getMaxHealth() * 0.5F;
                case NETHER -> Villages.ageOf(f.ownerId()) == Villages.Age.NETHER && friendBeenThrough(f, level);
            };
            if (brave) braved(f, fear, day);
        }
    }

    private static boolean friendBeenThrough(VillageFolkEntity f, ServerLevel level) {
        for (Social.Bond b : f.life().friends()) {
            for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
                if (a instanceof VillageFolkEntity o && o.displayNameCap().equals(b.name) && o.individual().beenNether) return true;
            }
        }
        return false;
    }

    /** A brave day against this fear (one a day counts): on the fifth, it is over it. True if it is. */
    public static boolean braved(VillageFolkEntity f, Fear fear, long day) {
        Individual.Self s = f.individual();
        if (!s.fears.contains(fear)) return true;
        String key = f.getUUID() + "/" + fear.name();
        if (BRAVE_DAY.getOrDefault(key, -1L) == day) return false;
        BRAVE_DAY.put(key, day);
        int c = s.courage.getOrDefault(fear, 0) + 1;
        if (c < COURAGE) {
            s.courage.put(fear, c);
            return false;
        }
        s.courage.remove(fear);
        s.fears.remove(fear);
        s.overcome.add(fear.word + ", on day " + (day + 1));
        f.persona().remember(day, "I got over my fear of " + fear.word, 7);
        if (f.ownerId() != null) Villages.tell(f.ownerId(), day, f.displayNameCap() + " is no longer afraid of " + fear.word);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "You know what? I'm not afraid of " + fear.word + " any more!",
            "Fancy that — " + fear.word + " doesn't frighten me now."));
        return true;
    }

    // ------------------------------------------------------------------ in words

    static String cardLine(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        List<String> out = new ArrayList<>();
        for (Fear fear : s.fears) {
            int c = s.courage.getOrDefault(fear, 0);
            out.add(fear.word + effect(fear) + (c > 0 ? " (getting braver: " + c + " of " + COURAGE + ")" : ""));
        }
        String line = out.isEmpty() ? "nothing much" : String.join("; ", out);
        if (!s.overcome.isEmpty()) line += "; got over " + s.overcome.get(s.overcome.size() - 1);
        return line;
    }

    private static String effect(Fear fear) {
        return switch (fear) {
            case DARK -> ": home before dusk";
            case DEEP_WATER -> ": won't fish or row";
            case HEIGHTS -> ": won't climb the bell tower";
            case NETHER -> ": stays this side of the gateway";
            case CROWDS -> ": keeps away from feasts";
            case MONSTERS -> ": runs at the sight of one";
        };
    }

    static String gossip(String n, Fear fear) {
        return switch (fear) {
            case DARK -> n + "'s scared of the dark, you know. Home before the lamps are lit, every night.";
            case DEEP_WATER -> n + " won't go near deep water. Wouldn't set foot in a boat for a sack of emeralds.";
            case HEIGHTS -> "Don't ask " + n + " to ring the bell — " + n + " can't abide heights.";
            case NETHER -> n + "'s scared stiff of the Nether. Goes white at the word.";
            case CROWDS -> "You'll not see " + n + " at the feast. Too many people, " + n + " says.";
            case MONSTERS -> n + " runs a mile at the sight of a zombie. Can't blame them, mind.";
        };
    }

    /** Talk: what it says of its fears ("What are you afraid of?"). */
    @Nullable
    static String talk(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        if (s.fears.isEmpty()) return null;                      // (one it got over is in its story: Backstory)
        Fear fear = s.fears.iterator().next();
        return "Truth be told, I'm " + fear.said + ".";
    }
}
