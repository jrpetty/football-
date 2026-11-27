package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [batchB] The town's seasons.
 *
 * <p>The town's year is four weeks (TownCalendar): twenty-eight days, counted from its Founding Day. It
 * is cut into four seasons of a week each, as the weeks already fall: spring from Founding Day (the first
 * seven days of every year), then summer, autumn and winter. A town counts its own seasons, not the
 * world's: two towns founded a fortnight apart are a season apart, and that is how folk who walk between
 * them talk of it ("it's winter up there already").
 *
 * <p>What a season changes:
 * <ul>
 * <li><b>The tended fields</b> (Fields): the growth a farmer's care adds over the wild is quicker in
 *     spring and summer and slower in autumn and winter, and never stops (the game's own growth goes on
 *     whatever the season, and the tended field always grows at least that). With the fields at twice
 *     the wild's pace, a tended field grows 2.3 times as fast as the wild in spring, 2.15 in summer, 1.8
 *     in autumn and 1.55 in winter: a town plants and grows in the spring, and lives off its stores in the
 *     winter, a little.</li>
 * <li><b>The year's festivals</b> (Festivals, Fair, Midwinter): the May dance, the midsummer bonfire, the
 *     fair, the harvest festival and midwinter, each on its own day of its season; and in a snowy town's
 *     winter the children's snowmen (Winter).</li>
 * <li><b>What is said</b>: the board, the town's books, the crier and the gazette say what season it is,
 *     and folk mention it now and then (the blossom, the long days, the leaves turning, the cold).</li>
 * </ul>
 * The season's first day goes into the chronicle. An operator can turn a town's calendar to any day of
 * its year (/village season set), for the tests and the pictures; the turn is kept with the world.
 */
public final class Seasons {

    private Seasons() {}

    /** A season's length, in days. */
    public static final int DAYS = 7;

    public enum Season {
        SPRING("spring", 1.30), SUMMER("summer", 1.15), AUTUMN("autumn", 0.80), WINTER("winter", 0.55);

        /** "spring", for sentences. */
        public final String word;
        /** How quickly the growth a farmer's care adds comes, against the town's set pace (1.0). */
        public final double growth;

        Season(String word, double growth) {
            this.word = word;
            this.growth = growth;
        }

        public String cap() {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
    }

    // ------------------------------------------------------------------ the count

    /** The day the town counts its years from (its founding), or nought for a town with no history yet. */
    private static long from(@Nullable UUID village) {
        long f = village == null ? -1 : FoundingDay.founded(village);
        return f < 0 ? 0 : f;
    }

    /** The day of the town's year, nought to twenty-seven: nought is its Founding Day, the first of spring. */
    public static int dayOfYear(@Nullable UUID village, long day) {
        int turned = village == null ? 0 : Festivals.turned(village);
        return (int) Math.floorMod(day - from(village) + turned, (long) TownCalendar.YEAR_DAYS);
    }

    /** The town's year, counted from one (its first year is one; its first Founding Day begins its second). */
    public static int year(@Nullable UUID village, long day) {
        int turned = village == null ? 0 : Festivals.turned(village);
        return (int) Math.max(1, Math.floorDiv(day - from(village) + turned, (long) TownCalendar.YEAR_DAYS) + 1);
    }

    public static Season season(@Nullable UUID village, long day) {
        return Season.values()[dayOfYear(village, day) / DAYS];
    }

    /** The day of its season, one to seven. */
    public static int dayInSeason(@Nullable UUID village, long day) {
        return dayOfYear(village, day) % DAYS + 1;
    }

    /** The world's day on which this day of the town's year falls, in the year that holds {@code day}. */
    public static long dayOf(@Nullable UUID village, long day, int dayOfYear) {
        return day - dayOfYear(village, day) + dayOfYear;
    }

    /** The world's day now (all the levels keep the overworld's clock). */
    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ the fields

    /**
     * A tended field's pace in this season (Fields.multiplier): the growth the farmer's care adds over the
     * wild ({@code m} - 1), quickened or slowed by the season. Never under the wild's own pace.
     */
    static double tended(VillageFolkEntity f, double m) {
        if (m <= 1.0 || f.ownerId() == null) return m;
        return 1.0 + (m - 1.0) * season(f.ownerId(), f.level().getDayTime() / 24000L).growth;
    }

    /** The books' word on the season's fields, after Fields.word: "; it is summer: a tended field at 2.15x". */
    static String fieldsNote(UUID village) {
        double base = AssistantConfig.villageCropGrowth();
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (base <= 1.0 || server == null) return "";
        Season s = season(village, server.overworld().getDayTime() / 24000L);
        return String.format(Locale.ROOT, "; it is %s, and a tended field grows at %.2fx", s.word, 1.0 + (base - 1.0) * s.growth);
    }

    // ------------------------------------------------------------------ the turn of the season

    /** Its first day: the season into the chronicle, once (Festivals.tick, after first light). */
    static void turn(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int key = year(id, day) * 4 + season(id, day).ordinal();
        Festivals.Town town = Festivals.town(id);
        if (town.toldSeason == key) return;
        boolean first = town.toldSeason < 0;
        town.toldSeason = key;
        Festivals.dirty();
        if (first && dayInSeason(id, day) > 1) return;           // a town met in the middle of a season: no news of it
        Season s = season(id, day);
        String said = switch (s) {
            case SPRING -> "spring came in, and the town's " + TownCalendar.ordinal(year(id, day)) + " year began";
            case SUMMER -> "summer came in";
            case AUTUMN -> "autumn came in, and the harvest with it";
            case WINTER -> Winter.snowy(level, v) ? "winter came in, and the snow with it" : "winter came in, with the first frosts";
        };
        Villages.tell(id, day, said);
    }

    // ------------------------------------------------------------------ where the player sees it

    /** "Summer, day 4 of 7 — the town's second year". */
    public static String dateLine(UUID village, long day) {
        return season(village, day).cap() + ", day " + dayInSeason(village, day) + " of " + DAYS + " — the town's "
            + TownCalendar.ordinal(year(village, day)) + " year";
    }

    /** The board (TownCalendar.board): the season, and the next festival or today's. */
    static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = today(level);
        Season s = season(village, day);
        out.add("RN|" + dateLine(village, day) + ". " + switch (s) {
            case SPRING -> "The fields grow quickly.";
            case SUMMER -> "The fields grow well.";
            case AUTUMN -> "The fields grow slowly.";
            case WINTER -> "The fields grow slowest now.";
        });
        out.addAll(Festivals.boardLines(level, village, day));
        return out;
    }

    /** The town's books (TownCalendar.book, the News page): the season, the fields, the year's festivals. */
    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = today(level);
        Season s = season(village, day);
        out.add("Season: " + s.word + ", day " + dayInSeason(village, day) + " of " + DAYS + " (day " + (dayOfYear(village, day) + 1)
            + " of the town's " + TownCalendar.ordinal(year(village, day)) + " year).");
        double base = AssistantConfig.villageCropGrowth();
        if (base > 1.0) {
            out.add(String.format(Locale.ROOT, "The fields in %s: a tended crop grows at %.2fx the wild (%.2fx in spring, %.2fx in summer, "
                    + "%.2fx in autumn, %.2fx in winter).", s.word, 1.0 + (base - 1.0) * s.growth,
                1.0 + (base - 1.0) * Season.SPRING.growth, 1.0 + (base - 1.0) * Season.SUMMER.growth,
                1.0 + (base - 1.0) * Season.AUTUMN.growth, 1.0 + (base - 1.0) * Season.WINTER.growth));
        }
        out.addAll(Festivals.bookLines(level, village, day));
        return out;
    }

    /** The crier's word (Crier.script): the day of the season, and tonight's festival or tomorrow's. Null for none. */
    @Nullable
    public static String cry(UUID village, long day) {
        String date = "It's the " + TownCalendar.ordinal(dayInSeason(village, day)) + " day of " + season(village, day).word + ".";
        String feast = Festivals.cryLine(village, day);
        return feast == null ? date : date + " " + feast;
    }

    /**
     * Talk of the season, now and then (Smalltalk.topic): the blossom, the long days, the leaves turning, the
     * cold (snow talk where it snows, frost talk where it does not), and a festival in the next day or two.
     * Each as {open, answer, last word}; empty, as often as not.
     */
    /** For the tests: the talk of the season these two might have, on this throw of the dice. */
    public static List<String[]> talkForTests(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        return talk(a, b, level, r);
    }

    static List<String[]> talk(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        List<String[]> out = new ArrayList<>();
        UUID village = a.ownerId();
        if (village == null) return out;
        long day = today(level);
        String coming = Festivals.talkOf(village, day);
        if (coming != null && r.nextBoolean()) {
            out.add(new String[]{ "Are you going to " + coming + "?", FolkTalk.pick(r, "Wouldn't miss it for the world!",
                "Of course — the whole town will be there.", "If my knees let me!"), FolkTalk.pick(r, "See you there, then.", "") });
            return out;
        }
        if (r.nextInt(3) != 0) return out;                         // now and then, not every time
        Villages.Village v = Villages.get(village);
        switch (season(village, day)) {
            case SPRING -> out.add(new String[]{ FolkTalk.pick(r, "The blossom's out at last.", "Spring at last! Smell that air."),
                FolkTalk.pick(r, "Planting time — the fields will be busy.", "Everything's growing like mad."),
                FolkTalk.pick(r, "Lovely.", "Long may it last.", "") });
            case SUMMER -> out.add(new String[]{ FolkTalk.pick(r, "Warm one today.", "Long days, these. I love the summer."),
                FolkTalk.pick(r, "The crops are coming on a treat.", "Too warm to work, if you ask me."),
                FolkTalk.pick(r, "Ha! True enough.", "") });
            case AUTUMN -> out.add(new String[]{ FolkTalk.pick(r, "The leaves are turning.", "Nights are drawing in."),
                FolkTalk.pick(r, "Best get the harvest in before the cold.", "Harvest festival soon, mind."),
                FolkTalk.pick(r, "Mm. Where does the year go?", "") });
            case WINTER -> {
                if (v != null && Winter.snowy(level, v)) {
                    out.add(new String[]{ FolkTalk.pick(r, "Cold enough to freeze your ears off!", "Snow again! Look at it come down.",
                            "Brr. It's bitter out today."),
                        FolkTalk.pick(r, "Mind the ice on the square.", "The children will be out building snowmen.",
                            "I can't feel my fingers."),
                        FolkTalk.pick(r, "Wrap up warm.", "Roll on spring.", "") });
                } else {
                    out.add(new String[]{ FolkTalk.pick(r, "A hard frost this morning.", "Frost on the fields again.", "Chilly today, isn't it?"),
                        FolkTalk.pick(r, "Nothing grows much in this cold.", "My breath was smoking on the way to work."),
                        FolkTalk.pick(r, "Wrap up warm, I say.", "Roll on spring.", "") });
                }
            }
        }
        return out;
    }
}
