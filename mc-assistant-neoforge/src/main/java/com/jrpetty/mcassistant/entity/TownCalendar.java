package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The town's calendar: the bell that keeps its day (TownBell), the round birthdays its folk keep
 * (Birthdays) and the day once a year it keeps for its founding (FoundingDay) — what they have in
 * common, and what of them is kept with the world.
 *
 * <p><b>The town's year.</b> Nothing in a folk's life measures a year: folk count their ages a
 * year to every five days once grown (six to the day, as children), so a year by their count is
 * three days — a feast every third evening. The town counts its own years by the weeks it already
 * keeps (the rest day, market day, the council's sitting, the feast): four of them, twenty-eight days.
 * Long enough that its Founding Day is an occasion, short enough that a town sees one every few
 * evenings' play, and a folk lives through a good many of them (a founder seventy-five days to two
 * hundred and forty-odd, about a hundred and sixty as a rule; one born in the village a hundred and
 * fifty to two hundred and fifty: VillageFolkEntity.DAYS_A_YEAR).
 *
 * <p>Kept with the world (the rest of a village rides on its folk, but these are dates): the day's
 * bells, so a restart does not ring them twice or let a town lie in; the last birthday each folk kept,
 * so its friends do not give twice; and the last Founding Day each town kept.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class TownCalendar extends SavedData {

    /** The town's year, in days: four weeks. */
    public static final int YEAR_DAYS = 28;

    private static final String ID = "mc_assistant_calendar";

    /** The day's bells, a village at a time: the bell-day, and each peal rung (or let go by) in it. */
    private final Map<UUID, CompoundTag> bells = new HashMap<>();
    /** The day each folk last kept a birthday. */
    private final Map<UUID, Long> birthdays = new HashMap<>();
    /** The last Founding Day each town kept (its day). */
    private final Map<UUID, Long> founding = new HashMap<>();
    /** Each town's bell frame: where it stands and of what (BellFrame). */
    private final Map<UUID, CompoundTag> frames = new HashMap<>();

    public TownCalendar() {}

    /** Everything that is only in memory forgotten (the tests share one JVM; a new world is a new start). */
    public static void resetForTests() {
        TownBell.resetForTests();
        BellFrame.resetForTests();
        Birthdays.resetForTests();
        FoundingDay.resetForTests();
        Crier.resetForTests();
    }

    // ------------------------------------------------------------------ the clock

    /** A time of day as a clock shows it: "5:31", "12:02", "18:30". (Minecraft's day starts at six.) */
    public static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        long hours = (t / 1000L + 6L) % 24L;
        long minutes = (t % 1000L) * 60L / 1000L;
        return hours + ":" + (minutes < 10 ? "0" : "") + minutes;
    }

    private static final String[] ORDINALS = { "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
        "eighth", "ninth", "tenth", "eleventh", "twelfth" };

    /** "first", "second" ... "twelfth", then "13th". */
    public static String ordinal(long n) {
        if (n >= 0 && n < ORDINALS.length) return ORDINALS[(int) n];
        long tens = n % 100;
        String end = tens >= 11 && tens <= 13 ? "th" : switch ((int) (n % 10)) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + end;
    }

    private static final String[] NUMBERS = { "nought", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen" };
    private static final String[] TENS = { "", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

    /** An age in words, as it is said on a birthday: "twelve", "forty", "seventy-two". */
    public static String inWords(int n) {
        if (n < 0) return Integer.toString(n);
        if (n < 20) return NUMBERS[n];
        if (n < 100) return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + NUMBERS[n % 10]);
        if (n == 100) return "a hundred";
        return Integer.toString(n);
    }

    // ------------------------------------------------------------------ the folk's part

    /**
     * From each folk's tick (VillageFolkEntity.aiStep): the bell-ringer to its bell, folk answering
     * the bells (to the midday meal, home at dusk), and friends going round with a birthday present.
     * True while one of them has it in hand.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || f.ownerId() == null || f.isHired()) return false;
        if (Crier.hold(f, level)) return true;                // [townlife] the noon news cried on the square (Crier)
        if (TownBell.hold(f, level)) return true;
        return Birthdays.hold(f, level);
    }

    /**
     * Is this folk about the calendar's business just now — ringing the bell, at its midday meal, on its
     * way home at dusk, taking a friend a birthday present? Its own work waits (VillageFolkEntity.onShift),
     * and its evening too (eveningSocial), till it is done.
     */
    public static boolean busy(VillageFolkEntity f) {
        return TownBell.busy(f) || Birthdays.busy(f) || Crier.busy(f);
    }

    // ------------------------------------------------------------------ the town's part

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % TownBell.EVERY != 3) return;
        com.jrpetty.mcassistant.Guard.run("the town bell", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    TownBell.tick(level, v);
                    if (tick % 20 == 3) Crier.tick(level, v);       // [townlife] the town crier at noon
                    if (tick % 100 == 3) Birthdays.tick(level, v);
                }
            }
        });
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The board's lines (the right-hand column): today's bells, and the town's coming days. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String bells = TownBell.line(level, village);
        if (bells != null) out.add("RN|" + bells);
        long day = level.getDayTime() / 24000L;
        StringBuilder days = new StringBuilder();
        String founding = FoundingDay.line(village, day);
        if (founding != null) days.append(founding).append('.');
        List<String> week = Birthdays.thisWeek(level, village, day);
        if (!week.isEmpty()) {
            if (days.length() > 0) days.append(' ');
            days.append("Birthdays this week: ").append(String.join("; ", week)).append('.');
        }
        if (days.length() > 0) out.add("RM|" + days);
        return out;
    }

    /** The town's books (the News page): the day's bells, its coming days, and whose birthday it is. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>(TownBell.book(level, village));
        long day = level.getDayTime() / 24000L;
        String founding = FoundingDay.line(village, day);
        if (founding != null) out.add(founding + ".");
        List<String> week = Birthdays.thisWeek(level, village, day);
        out.add(week.isEmpty() ? "No birthdays this week." : "Birthdays this week: " + String.join("; ", week) + ".");
        return out;
    }

    // ------------------------------------------------------------------ kept with the world

    @Nullable
    static TownCalendar of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(TownCalendar::new, TownCalendar::load, null), ID);
    }

    /** A village's day of bells, as last written down, or null. */
    @Nullable
    static CompoundTag bells(UUID village) {
        TownCalendar c = of();
        return c == null ? null : c.bells.get(village);
    }

    static void bells(UUID village, CompoundTag day) {
        TownCalendar c = of();
        if (c == null) return;
        c.bells.put(village, day);
        c.setDirty();
    }

    /** The day this folk last kept a birthday, or a long time ago. */
    static long birthdayKept(UUID folk) {
        TownCalendar c = of();
        return c == null ? Long.MIN_VALUE : c.birthdays.getOrDefault(folk, Long.MIN_VALUE);
    }

    static void birthdayKept(UUID folk, long day) {
        TownCalendar c = of();
        if (c == null) return;
        c.birthdays.put(folk, day);
        // The long dead and the long gone: a birthday kept months ago is never asked after again.
        if (c.birthdays.size() > 256) c.birthdays.values().removeIf(d -> d < day - TownCalendar.YEAR_DAYS * 2L);
        c.setDirty();
    }

    /** This town's bell frame, as written down, or null. */
    @Nullable
    static CompoundTag frame(UUID village) {
        TownCalendar c = of();
        return c == null ? null : c.frames.get(village);
    }

    static void frame(UUID village, @Nullable CompoundTag frame) {
        TownCalendar c = of();
        if (c == null) return;
        if (frame == null) c.frames.remove(village);
        else c.frames.put(village, frame);
        c.setDirty();
    }

    /** The day this town last kept its Founding Day, or a long time ago. */
    static long foundingKept(UUID village) {
        TownCalendar c = of();
        return c == null ? Long.MIN_VALUE : c.founding.getOrDefault(village, Long.MIN_VALUE);
    }

    static void foundingKept(UUID village, long day) {
        TownCalendar c = of();
        if (c == null) return;
        c.founding.put(village, day);
        c.setDirty();
    }

    public static TownCalendar load(CompoundTag tag, HolderLookup.Provider registries) {
        TownCalendar c = new TownCalendar();
        for (Tag t : tag.getList("Bells", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (one.hasUUID("Id")) c.bells.put(one.getUUID("Id"), one.getCompound("Day"));
        }
        for (Tag t : tag.getList("Birthdays", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (one.hasUUID("Id")) c.birthdays.put(one.getUUID("Id"), one.getLong("Day"));
        }
        for (Tag t : tag.getList("Founding", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (one.hasUUID("Id")) c.founding.put(one.getUUID("Id"), one.getLong("Day"));
        }
        for (Tag t : tag.getList("Frames", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (one.hasUUID("Id")) c.frames.put(one.getUUID("Id"), one.getCompound("Frame"));
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag b = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : bells.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.put("Day", e.getValue());
            b.add(one);
        }
        tag.put("Bells", b);
        ListTag d = new ListTag();
        for (Map.Entry<UUID, Long> e : birthdays.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.putLong("Day", e.getValue());
            d.add(one);
        }
        tag.put("Birthdays", d);
        ListTag f = new ListTag();
        for (Map.Entry<UUID, Long> e : founding.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.putLong("Day", e.getValue());
            f.add(one);
        }
        tag.put("Founding", f);
        ListTag fr = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : frames.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.put("Frame", e.getValue());
            fr.add(one);
        }
        tag.put("Frames", fr);
        return tag;
    }
}
