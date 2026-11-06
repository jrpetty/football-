package com.jrpetty.mcassistant.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The evenings a whole village keeps together.
 *
 * <ul>
 *   <li><b>A wedding</b> the evening after two folk have pledged themselves: the
 *       couple at the heart, the village in a ring round them, the bell, their vows,
 *       the cheering and the hearts.</li>
 *   <li><b>A celebration</b> the evening a village comes into a new age: everybody
 *       out, and fireworks over the heart.</li>
 *   <li><b>A vigil</b> the evening after a death: the village quiet at the heart.</li>
 *   <li><b>A feast</b> every seventh evening: music, food passed round, and
 *       everybody the better for it the next day.</li>
 * </ul>
 *
 * <p>Work comes first: only folk off work attend, and it is over at bedtime.
 */
public final class Gatherings {

    private Gatherings() {}

    public enum Kind { WEDDING, HONOUR, CELEBRATION, VIGIL, FEAST }

    private static final Map<UUID, Long> HONOURED_ON = new ConcurrentHashMap<>();
    private static final Map<UUID, String> HONOURED = new ConcurrentHashMap<>();

    /** The village celebrates a player it has named its hero, tonight. */
    public static void honour(UUID village, String name, long day) {
        HONOURED_ON.put(village, day);
        HONOURED.put(village, name);
    }

    public record Wedding(UUID a, UUID b, String names, long day) {}

    private static final Map<UUID, Deque<Wedding>> WEDDINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DIED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> DIED_NAME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BELL = new ConcurrentHashMap<>();
    private static final Map<String, Long> DONE = new ConcurrentHashMap<>();

    public static void pledged(UUID village, VillageFolkEntity a, VillageFolkEntity b, long day) {
        WEDDINGS.computeIfAbsent(village, k -> new ArrayDeque<>())
            .addLast(new Wedding(a.getUUID(), b.getUUID(), a.displayNameCap() + " and " + b.displayNameCap(), day));
    }

    /** Tonight's feast, paid for by a player (Dealings.sponsor): who, and which night. */
    private static final Map<UUID, Long> SPONSORED_ON = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SPONSOR = new ConcurrentHashMap<>();

    public static void sponsor(UUID village, String name, long day) {
        SPONSORED_ON.put(village, day);
        SPONSOR.put(village, name);
    }

    public static boolean sponsored(UUID village, long day) {
        return SPONSORED_ON.getOrDefault(village, -1L) == day;
    }

    public static void mourn(UUID village, String name, long day) {
        DIED.put(village, day);
        DIED_NAME.put(village, name);
    }

    public static void resetForTests() {
        WEDDINGS.clear();
        HONOURED_ON.clear();
        HONOURED.clear();
        DIED.clear();
        DIED_NAME.clear();
        LAST_BELL.clear();
        DONE.clear();
        SPONSORED_ON.clear();
        SPONSOR.clear();
    }

    /** What the village is gathering for this evening, if anything. */
    @Nullable
    public static Kind tonight(UUID village, long day) {
        Deque<Wedding> w = WEDDINGS.get(village);
        if (w != null) {
            w.removeIf(x -> day - x.day() > 2);
            if (!w.isEmpty()) return Kind.WEDDING;
        }
        Long honoured = HONOURED_ON.get(village);
        if (honoured != null && day - honoured <= 0) return Kind.HONOUR;
        long aged = Villages.agedOn(village);
        if (aged >= 0 && day - aged <= 0) return Kind.CELEBRATION;
        Long died = DIED.get(village);
        if (died != null && day - died <= 0) return Kind.VIGIL;
        if (day > 0 && day % 7 == 6 || sponsored(village, day)
            || Government.extraFeast(village, day)) return Kind.FEAST;      // [identity] the chaplain's mid-week feast day
        if (CityTree.saintsDay(village, day)) return Kind.FEAST;            // [perks] Saints' Days: a feast to open each season
        return null;
    }

    /** What the gathering is, in a few words, for the board and the news. */
    public static String describe(Kind kind, UUID village) {
        return switch (kind) {
            case WEDDING -> {
                Wedding w = wedding(village);
                yield w == null ? "a wedding" : "the wedding of " + w.names();
            }
            case HONOUR -> "a celebration for " + HONOURED.getOrDefault(village, "our hero");
            case CELEBRATION -> "fireworks for coming into " + Villages.ageOf(village).label;
            case VIGIL -> "a vigil for " + DIED_NAME.getOrDefault(village, "a friend");
            case FEAST -> "the village feast";
        };
    }

    /** The wedding has been held (Assemblies): the next couple, if any, is next. */
    static void wed(UUID village) {
        Deque<Wedding> w = WEDDINGS.get(village);
        if (w != null) w.pollFirst();
    }

    @Nullable
    static Wedding wedding(UUID village) {
        Deque<Wedding> w = WEDDINGS.get(village);
        return w == null ? null : w.peekFirst();
    }

    private static void wedding(VillageFolkEntity f, ServerLevel server, @Nullable Wedding w, boolean bride,
                                boolean first, long day, UUID village) {
        RandomSource r = f.getRandom();
        if (w == null) return;
        long now = f.level().getGameTime();
        Long rang = LAST_BELL.get(village);
        if (rang == null || now - rang > 200) {
            LAST_BELL.put(village, now);
            server.playSound(null, f.villageCentre(), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 1.0F);
        }
        if (bride) {
            server.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.1, f.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
            if (first) {
                FolkTalk.speak(f, FolkTalk.pick(r, "I will.", "With all my heart.", "I do!"));
                f.persona().remember(day, "we were wed, " + w.names(), 10);
                f.persona().feasted(day);
                // The second of the two to say it closes the ceremony.
                String key = village + "/wed/" + w.a() + "/" + w.b();
                if (DONE.putIfAbsent(key, day) != null) {
                    Villages.tell(village, day, w.names() + " were wed");
                    Families.wed(village, w.a(), w.b(), day);      // the day kept, for its anniversaries
                }
            }
        } else if (first) {
            f.persona().remember(day, "I was at " + w.names() + "'s wedding", 3);
            f.persona().feasted(day);
            if (server.getEntity(w.a()) instanceof VillageFolkEntity a) f.life().feel(a.getUUID(), a.displayNameCap(), 4);
            if (server.getEntity(w.b()) instanceof VillageFolkEntity b) f.life().feel(b.getUUID(), b.displayNameCap(), 4);
        } else if (r.nextInt(4) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(r, "To the happy couple!", "Hooray!", "Doesn't she look lovely?",
                "About time, those two!", "Kiss! Kiss!"));
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            dance(f, r);
        }
    }

    /** A little jig. */
    private static void dance(VillageFolkEntity f, RandomSource r) {
        if (f.onGround() && r.nextInt(3) == 0) {
            f.getJumpControl().jump();
            f.setYRot(f.getYRot() + 45.0F * (r.nextBoolean() ? 1 : -1));
            f.swing(r.nextBoolean() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
        }
    }
}
