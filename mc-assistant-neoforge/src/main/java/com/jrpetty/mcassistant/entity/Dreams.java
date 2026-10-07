package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.LibraryRecords;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * [individual] A folk's dream: the life goal Persona calls its ambition, grown to the ones the brief of a life asks
 * for, worked towards, and changed when it comes true or can no longer.
 *
 * <ul>
 * <li><b>To marry</b>: it courts the friend it is fondest of who is free, a little warmer every day; it comes true when
 *     it has a partner.</li>
 * <li><b>To see the sea</b>: its favourite place is the water's edge; true the day it stands on a beach or the ocean.</li>
 * <li><b>A house of its own</b> and <b>to be rich</b>: it saves, passing up the café and the shop's treats; true when it
 *     owns its house, or is wealthy.</li>
 * <li><b>To write a book</b>: the library's authors take it on first, if it can write at all; true when a book of its
 *     own is on the shelves.</li>
 * <li><b>To go to the Nether</b>: it volunteers for the town's Nether party; true when it has been through and back.</li>
 * <li><b>To lead the town</b>: it stands at the elections with the town's good will behind it; true when it leads.</li>
 * <li><b>A big family</b>: four children at the least; a couple who want one have their children the sooner.</li>
 * </ul>
 * The old dreams (a master of its trade, a family, friends, a diamond, the Nether Age, a great work, a garden, never
 * hungry) go on as they were. A dream come true is a great lift to its spirits for days, and the chronicle's news;
 * a few days on it dreams something new. One it can never have now (the Nether to one afraid of it, a big family
 * past its years) it gives up for another.
 */
public final class Dreams {

    private Dreams() {}

    /** Has its dream (one of the new ones) come true? (VillageFolkEntity.dreamCameTrue) */
    public static boolean met(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        UUID village = f.ownerId();
        return switch (f.persona().ambition()) {
            case MARRY -> f.life().partner() != null;
            case SEE_THE_SEA -> s.seenSea;
            case OWN_HOUSE -> village != null && ownsHouse(f, village);
            case WRITE_BOOK -> village != null && wroteABook(f, village);
            case GO_NETHER -> s.beenNether;
            case LEAD -> f.isElder();
            case BIG_FAMILY -> f.life().children() >= 4;
            case RICH -> Wealth.tier(f) == Wealth.Tier.WEALTHY;
            default -> false;
        };
    }

    private static boolean ownsHouse(VillageFolkEntity f, UUID village) {
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        return h != null && h.tenure == Homes.Tenure.OWNED;
    }

    private static boolean wroteABook(VillageFolkEntity f, UUID village) {
        for (LibraryRecords.Title t : LibraryRecords.shelf(village).books) {
            if (f.getUUID().equals(t.authorId) || t.author.equals(f.displayNameCap())) return true;
        }
        return false;
    }

    /** Its dream came true today (VillageFolkEntity.dreamCameTrue): the days of delight it brings, and its record. */
    public static void cameTrue(VillageFolkEntity f, long day) {
        Individual.Self s = f.individual();
        s.dreamMetDay = day;
        s.pastDreams.add(f.persona().ambition().done);
    }

    /** In the chronicle: "Fen's dream came true: to see the sea". */
    public static String news(VillageFolkEntity f) {
        return f.displayNameCap() + "'s dream came true: " + f.persona().ambition().hope;
    }

    // ------------------------------------------------------------------ shaped, changed

    /** Its dream, made to fit it: never the Nether to one afraid of it; the sea to a folk of the water; a book to a reader. */
    static void shape(VillageFolkEntity f, RandomSource r) {
        Persona p = f.persona();
        if (!p.rolled()) return;
        Individual.Self s = f.individual();
        if (!possible(f, p.ambition())) p.ambition = pick(f, r);
        if (s.literate && p.hobby() == Persona.Hobby.READING && r.nextInt(3) == 0) p.ambition = Persona.Ambition.WRITE_BOOK;
        if (f.stationTask() == StationTask.FISH && !s.fears.contains(Fears.Fear.DEEP_WATER) && r.nextInt(4) == 0) p.ambition = Persona.Ambition.SEE_THE_SEA;
        if (p.ambition == Persona.Ambition.SEE_THE_SEA && !s.fears.contains(Fears.Fear.DEEP_WATER)) s.place = Habits.Place.QUAY;
    }

    static boolean possible(VillageFolkEntity f, Persona.Ambition a) {
        Individual.Self s = f.individual();
        int age = f.isBaby() ? 0 : f.ageYears();
        return switch (a) {
            case GO_NETHER -> !s.fears.contains(Fears.Fear.NETHER) && !Quirks.has(f, Quirks.Quirk.HOMEBODY);   // [perks] nor a homebody
            case SEE_THE_SEA -> !s.fears.contains(Fears.Fear.DEEP_WATER);
            case WRITE_BOOK -> s.literate || f.isBaby();
            case BIG_FAMILY, FAMILY -> age < 56;
            case MARRY -> age < 75 && f.life().partner() == null;
            default -> true;
        };
    }

    private static Persona.Ambition pick(VillageFolkEntity f, RandomSource r) {
        Persona.Ambition[] all = Persona.Ambition.values();
        for (int i = 0; i < 20; i++) {
            Persona.Ambition a = all[r.nextInt(all.length)];
            if (a != f.persona().ambition() && possible(f, a) && !f.individual().pastDreams.contains(a.done)) return a;
        }
        return Persona.Ambition.FRIENDS;
    }

    /** Every twenty seconds (Individual.tick): what it has done, a new dream after one come true, an impossible one let go. */
    static void tick(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        Persona p = f.persona();
        if (!p.rolled() || !s.rolled) return;
        long day = level.getDayTime() / 24000L;
        if (!s.seenSea && level.getBiome(f.blockPosition()).is(BiomeTags.IS_OCEAN)
                || !s.seenSea && level.getBiome(f.blockPosition()).is(BiomeTags.IS_BEACH)) {
            s.seenSea = true;
            p.remember(day, "I stood by the sea", p.ambition() == Persona.Ambition.SEE_THE_SEA ? 8 : 3);
        }
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) s.beenNether = true;
        if (p.ambitionMet()) {
            if (s.dreamMetDay >= 0 && day - s.dreamMetDay >= 4) {
                Persona.Ambition next = pick(f, f.getRandom());
                p.ambition = next;
                p.ambitionMet = false;
                p.remember(day, "I've a new dream now: " + next.hope, 5);
            }
            return;
        }
        if (!possible(f, p.ambition())) {
            Persona.Ambition was = p.ambition();
            p.ambition = pick(f, f.getRandom());
            s.pastDreams.add("let go of " + was.hope);
            p.remember(day, "I gave up dreaming of " + was.hope.replaceFirst("^to ", "") + "; now I hope " + p.ambition.hope, 5);
            return;
        }
        if (p.ambition() == Persona.Ambition.MARRY) court(f, level, day);
    }

    // ------------------------------------------------------------------ worked towards

    /** To marry: a little warmer each day toward the friend it is fondest of who is free, and they to it. */
    static void court(VillageFolkEntity f, ServerLevel level, long day) {
        if (f.life().partner() != null || f.isBaby()) return;
        VillageFolkEntity best = null;
        int warmest = Social.FRIEND - 1;
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (!(a instanceof VillageFolkEntity o) || o == f || o.isBaby() || o.life().partner() != null) continue;
            if (f.parentIds().contains(o.getUUID()) || o.parentIds().contains(f.getUUID())) continue;
            int w = f.life().affinity(o.getUUID());
            if (w > warmest) { warmest = w; best = o; }
        }
        if (best == null) return;
        String key = "court/" + f.getUUID();
        if (Long.valueOf(day).equals(COURTED.get(key))) return;
        COURTED.put(key, day);
        f.life().feel(best.getUUID(), best.displayNameCap(), 3);
        best.life().feel(f.getUUID(), f.displayNameCap(), 2);
    }

    private static final java.util.Map<String, Long> COURTED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void resetForTests() {
        COURTED.clear();
    }

    /** Tests: this dream, not yet come true. */
    public static void dreamForTests(VillageFolkEntity f, Persona.Ambition a) {
        f.ensurePersona();
        f.persona().ambition = a;
        f.persona().ambitionMet = false;
        f.individual().dreamMetDay = -1;
    }

    /** Tests: the day's courting, now. */
    public static void courtForTests(VillageFolkEntity f) {
        if (f.level() instanceof ServerLevel level) court(f, level, level.getDayTime() / 24000L);
    }

    /** Saving for a house of its own, or to be rich: the café and the shop's treats passed up. */
    public static boolean saving(VillageFolkEntity f) {
        Persona p = f.persona();
        return p.rolled() && !p.ambitionMet() && (p.ambition() == Persona.Ambition.OWN_HOUSE || p.ambition() == Persona.Ambition.RICH);
    }

    /** Authors.writerish: one who dreams of writing a book will, if it can write. */
    public static boolean wantsToWrite(VillageFolkEntity f) {
        return f.persona().rolled() && !f.persona().ambitionMet() && f.persona().ambition() == Persona.Ambition.WRITE_BOOK
            && f.individual().literate;
    }

    /** Authors.fitness: the dreamer first. */
    public static double writerBonus(VillageFolkEntity f) {
        return wantsToWrite(f) ? 30.0 : 0.0;
    }

    /** NetherRunners.appoint: one who dreams of the Nether volunteers first for the runners. */
    public static boolean volunteersForNether(VillageFolkEntity f) {
        return f.persona().rolled() && !f.persona().ambitionMet() && f.persona().ambition() == Persona.Ambition.GO_NETHER
            && !Fears.dreads(f, Fears.Fear.NETHER) && !f.isBaby();
    }

    /** Elections.nominate: one who dreams of leading stands with a will. */
    public static int ambitionToLead(VillageFolkEntity f) {
        return f.persona().rolled() && !f.persona().ambitionMet() && f.persona().ambition() == Persona.Ambition.LEAD ? 25 : 0;
    }

    /** VillageFolkEntity.raisedAChild: a couple who want a big family have their children sooner (lower odds against). */
    public static int birthOdds(VillageFolkEntity a, VillageFolkEntity b, int odds) {
        boolean wants = a.persona().ambition() == Persona.Ambition.BIG_FAMILY && !a.persona().ambitionMet()
            || b.persona().ambition() == Persona.Ambition.BIG_FAMILY && !b.persona().ambitionMet();
        return wants ? Math.max(1, odds - 1) : odds;
    }

    // ------------------------------------------------------------------ in words

    /** FolkTalk.dreams: how far it has got with one of the new dreams. */
    public static String progress(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        return switch (f.persona().ambition()) {
            case MARRY -> f.life().friends().isEmpty() ? " Nobody special yet." : " There's somebody I'm sweet on, mind.";
            case SEE_THE_SEA -> " I've never once seen it. They say it goes on for ever.";
            case OWN_HOUSE -> " I'm putting by what I can — " + f.purse() + " coins so far.";
            case WRITE_BOOK -> s.literate ? " I've the story in my head. It just needs writing down." : " I'd have to learn my letters first.";
            case GO_NETHER -> " Next time a party goes through the gateway, I'm going with it.";
            case LEAD -> " I'll stand at the next election, see if I don't.";
            case BIG_FAMILY -> " " + f.life().children() + " so far. The more the merrier!";
            case RICH -> " " + f.purse() + " coins put by. A long way to go.";
            default -> "";
        };
    }

    static String cardLine(VillageFolkEntity f) {
        Persona p = f.persona();
        if (!p.rolled()) return "";
        String line = p.ambitionMet() ? "came true: " + p.ambition().done : p.ambition().hope;
        if (saving(f)) line += " (saving for it)";
        else if (volunteersForNether(f)) line += " (first to volunteer)";
        else if (wantsToWrite(f)) line += " (the library's first choice of writer)";
        return line;
    }
}
