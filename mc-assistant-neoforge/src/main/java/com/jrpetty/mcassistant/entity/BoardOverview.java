package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [folk-ui] The board's Overview at a glance (client/CityScreen): the readings the books do not keep day by day (safety
 * and looks, each with its parts; the folk's mood and work as they stand), and what needs attention now, each alert
 * with the page that explains it. Built with the rest of the books (Annals.snapshot) when a player opens them, never on
 * a tick; everything read is already worked out elsewhere (Contentment's cached view, the books, the folk's fields).
 */
public final class BoardOverview {

    private BoardOverview() {}

    /** How bad an alert is: the client colours and orders them by this. */
    public static final int INFO = 0, WARN = 1, DANGER = 2;
    /** Food put by, in days, below which it is a worry, and below which it is an emergency. */
    private static final double FOOD_LOW = 4.0, FOOD_CRITICAL = 2.0;
    /** Lying about the town, in items, that makes it look neglected. */
    private static final int LITTER = 40;

    /** The Overview's tag: "safety", "looks" (0..100) with their parts as words, "mood", "workers", "adults", "alerts". */
    public static CompoundTag report(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk, CompoundTag books) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        Contentment.View view = Contentment.of(level, id);
        // Safety: Contentment's own reading (0..10: the wall, the watch, a death, the alarm, the neighbours), as a hundred.
        out.putInt("safety", view.safety() * 10);
        // Looks: things to enjoy, how well the homes are furnished, and how tidy the streets are.
        CompoundTag decor = books.getCompound("decor");
        int homes = Math.max(1, decor.getInt("homes"));
        int lying = books.getCompound("storehouse").getInt("lying");
        int amen = Math.min(40, view.amenities() * 40 / 16), furnished = decor.getInt("well") * 30 / homes, tidy = 30 - Math.min(30, lying / 2);
        out.putInt("looks", Math.max(0, Math.min(100, amen + furnished + tidy)));
        out.putString("looks_why", "Things to enjoy " + amen + "/40 · homes well furnished " + furnished + "/30 · tidy streets " + tidy + "/30"
            + (lying > 0 ? " (" + lying + " items lying about)" : ""));
        out.putString("safety_why", String.join("; ", view.good().isEmpty() && view.bad().isEmpty() ? List.of("quiet") : joinGoodBad(view)));
        int moodSum = 0, adults = 0, workers = 0, ill = 0, stuck = 0, noBed = 0;
        for (VillageFolkEntity f : folk) {
            moodSum += f.persona().mood();
            if (f.isBaby()) continue;
            adults++;
            if (f.stationTask() != AssistantEntity.StationTask.NONE) workers++;
            if (!Health.illWord(f).isEmpty()) ill++;
            if (f.stationTask() != AssistantEntity.StationTask.NONE && !f.missingEssentials().isEmpty()) stuck++;
            if (f.bedPos() == null) noBed++;
        }
        out.putInt("mood", folk.isEmpty() ? 0 : moodSum / folk.size());
        out.putInt("adults", adults);
        out.putInt("workers", workers);
        out.putInt("content", view.score());
        out.putString("content_word", view.word());
        out.put("alerts", alerts(level, id, books, adults - workers, ill, stuck, noBed, view, lying));
        return out;
    }

    private static List<String> joinGoodBad(Contentment.View view) {
        List<String> out = new ArrayList<>();
        for (String s : view.good()) out.add("+ " + s);
        for (String s : view.bad()) out.add("− " + s);
        return out;
    }

    /** What needs attention now, worst first, each with the page that explains it. */
    private static ListTag alerts(ServerLevel level, UUID id, CompoundTag books, int idle, int ill, int stuck, int noBed,
                                  Contentment.View view, int lying) {
        List<CompoundTag> all = new ArrayList<>();
        if (Raids.underAlarm(id)) add(all, DANGER, "The bell is ringing: the town is under attack", "Watch");
        if (WarAndPeace.onGuard(id)) add(all, DANGER, "The town is on a war footing", "War");
        int[] fd = books.getCompound("series").getIntArray("food_days10");
        if (fd.length > 0) {
            double days = fd[fd.length - 1] / 10.0;
            if (days < FOOD_CRITICAL) add(all, DANGER, String.format(java.util.Locale.ROOT, "Food for only %.1f days", days), "Stores");
            else if (days < FOOD_LOW) add(all, WARN, String.format(java.util.Locale.ROOT, "Food running low: %.1f days put by", days), "Stores");
        }
        int[] meals = Meals.yesterday(id);
        if (meals != null && meals.length > 1 && meals[1] > 0) add(all, WARN, meals[1] + (meals[1] == 1 ? " meal" : " meals") + " missed yesterday", "Folk");
        if (noBed > 0) add(all, WARN, noBed + (noBed == 1 ? " folk has" : " folk have") + " no bed", "Homes");
        if (stuck > 0) add(all, WARN, stuck + (stuck == 1 ? " folk can't work: short of tools or kit" : " folk can't work: short of tools or kit"), "Jobs");
        if (ill > 0) add(all, WARN, ill + (ill == 1 ? " folk is ill" : " folk are ill"), "Folk");
        if (view.score() < 40) add(all, WARN, "The town is " + view.word() + " (" + view.score() + "/100)", "Why");
        int[] coins = books.getCompound("series").getIntArray("coins");
        if (coins.length >= 4 && coins[coins.length - 1] < coins[coins.length - 2] && coins[coins.length - 2] < coins[coins.length - 3]
                && coins[coins.length - 3] < coins[coins.length - 4]) {
            add(all, WARN, "The treasury has fallen three days running", "Money");
        }
        if (idle > 0) add(all, INFO, idle + (idle == 1 ? " grown folk has" : " grown folk have") + " no trade yet", "Jobs");
        if (lying > LITTER) add(all, INFO, lying + " items lying about the streets", "Stores");
        int[] waiting = books.getCompound("series").getIntArray("waiting");
        int wait = waiting.length == 0 ? 0 : waiting[waiting.length - 1];
        if (wait > 0) add(all, INFO, wait + " waiting for a home", "Homes");
        all.sort((a, b) -> Integer.compare(b.getInt("level"), a.getInt("level")));
        ListTag out = new ListTag();
        out.addAll(all);
        return out;
    }

    private static void add(List<CompoundTag> all, int level, String text, String page) {
        CompoundTag c = new CompoundTag();
        c.putInt("level", level);
        c.putString("text", text);
        c.putString("page", page);
        all.add(c);
    }
}
