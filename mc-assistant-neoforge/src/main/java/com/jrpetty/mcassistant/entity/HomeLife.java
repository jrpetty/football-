package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [home-making] A household's life at home, as the rest of the town sees it: the one round the town calls (ShopsHomes's
 * quarter-minute), the folk's own time (its chores, then its home's furnishing), its spirits, and its card. The work is
 * in HomeMaking (the furniture) and Housekeeping (the stocks); this only puts them in their places in the day.
 */
public final class HomeLife {

    private HomeLife() {}

    /** The evening round runs from this hour (after the day's work, before bed) on. */
    static final long EVENING = 12500L, NIGHT = 23000L;
    /** The morning: from first light to the start of work. */
    static final long MORNING_FROM = 22800L, MORNING_TO = 2500L;

    private static final Map<UUID, Long> MORNED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        MORNED.clear();
        STOCKED.clear();
        STOCK_LOOK.clear();
        HomeMaking.resetForTests();
        Housekeeping.resetForTests();
    }

    /**
     * The town's round (ShopsHomes.tick, every quarter-minute): the households' plans for their homes kept; each home's
     * evening once a day (water, fuel, spoilage, cooking, remedies); the folk's mornings (breakfast at home, tools out,
     * winter wear) and its tool home at night.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        HomeMaking.tick(level, v);
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        boolean evening = t >= EVENING && t < NIGHT;
        boolean morning = t >= MORNING_FROM || t < MORNING_TO;
        if (!evening && !morning) {
            // The working day: one who slept late, or came home for it, takes the tool of its trade from home.
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping()) HomeStores.toolsOut(level, f);
            }
            return;
        }
        for (Homes.Home h : List.copyOf(Homes.homes(v.id()).values())) {
            if (h.members.isEmpty() || !level.isLoaded(h.anchor)) continue;
            if (evening) {
                Housekeeping.evening(level, v, h, day);
                for (VillageFolkEntity f : Homes.loadedMembers(v.id(), h)) Housekeeping.toolsHome(level, v.id(), f);
            } else {
                // The morning belongs to the day it ends in: before midnight's turn it is still yesterday's night.
                long morn = t >= MORNING_FROM ? day + 1 : day;
                for (VillageFolkEntity f : Homes.loadedMembers(v.id(), h)) {
                    if (f.isSleeping() || MORNED.getOrDefault(f.getUUID(), -1L) == morn) continue;
                    if (f.blockPosition().distSqr(h.anchor) > 16 * 16) continue;
                    MORNED.put(f.getUUID(), morn);
                    Housekeeping.morning(level, v, f, morn);
                }
            }
        }
    }

    /** Its own time (VillageFolkEntity.socialise): a chore its household wants doing, else the next piece for its home. */
    public static boolean ownTime(VillageFolkEntity f, ServerLevel level) {
        if (Housekeeping.chores(f, level)) return true;
        return HomeMaking.errand(f, level);
    }

    /**
     * Its spirits at home (VillageFolkEntity's mood): a home set up by its own hands (+3), one still bare days after it
     * moved in (-3); a cold house in winter (-6), no water at home (-3); a larder and a woodpile put by for the winter (+2).
     */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return m;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null || Homes.lodging(f)) return m;
        HomeMaking.Plan p = HomeMaking.load(village, h.anchor.asLong());
        if (p != null) {
            boolean byHand = false;
            for (HomeMaking.Piece q : p.pieces) if (q.state == HomeMaking.State.PLACED && q.how.own()) { byHand = true; break; }
            if (p.settled && byHand) { m += 3; why.add(new Object[]{"ownhands", 3}); }
            else if (!p.settled && p.since >= 0 && day - p.since >= 3) { m -= 3; why.add(new Object[]{"barehome", 3}); }
        }
        boolean[] hard = Housekeeping.hardship(village, h, day);
        Seasons.Season s = Housekeeping.season(village, day);
        if (hard[0] && s == Seasons.Season.WINTER) { m -= 6; why.add(new Object[]{"chillyhome", 6}); }
        if (hard[1]) { m -= 3; why.add(new Object[]{"nowater", 3}); }
        if ((s == Seasons.Season.AUTUMN || s == Seasons.Season.WINTER) && f.level() instanceof ServerLevel level && level.isLoaded(h.anchor)
                && level.getGameTime() >= STOCK_LOOK.getOrDefault(f.getUUID(), 0L)) {
            // Looked at once a minute (the counting goes through the house's boxes): stocked up for the winter.
            STOCK_LOOK.put(f.getUUID(), level.getGameTime() + 1200L);
            Housekeeping.Levels l = Housekeeping.levels(level, village, h);
            STOCKED.put(f.getUUID(), !l.shortOf(Housekeeping.Stock.FOOD) && (!l.stove() || !l.shortOf(Housekeeping.Stock.FUEL)));
        }
        if ((s == Seasons.Season.AUTUMN || s == Seasons.Season.WINTER) && STOCKED.getOrDefault(f.getUUID(), false)) {
            m += 2;
            why.add(new Object[]{"stocked", 2});
        }
        return m;
    }

    private static final Map<UUID, Boolean> STOCKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STOCK_LOOK = new ConcurrentHashMap<>();

    /** Its words for those spirits. */
    public static String moodWords(VillageFolkEntity f, String why, net.minecraft.util.RandomSource r) {
        return switch (why) {
            case "chillyhome" -> FolkTalk.pick(r, "It's bitter at home — not a stick to put on the stove.", "Our house is like ice. We've no fuel.");
            case "nowater" -> FolkTalk.pick(r, "We went without water last night. Somebody must get to the well.", "Not a drop in the house!");
            case "barehome" -> FolkTalk.pick(r, "Our place is still half empty. We'll get there.", "We've not even a table yet.");
            case "ownhands" -> FolkTalk.pick(r, "We set our home up ourselves, every stick of it.", "We made our own table, you know. Well — we did.");
            case "stocked" -> FolkTalk.pick(r, "Larder's full and the wood's in. Let the winter come.", "We're set for the winter, us.");
            default -> "";
        };
    }
}
