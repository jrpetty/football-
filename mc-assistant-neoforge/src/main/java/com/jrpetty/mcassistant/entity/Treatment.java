package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [identity] How a town treats a player, from its character, its laws and its history with players.
 *
 * <ul>
 * <li><b>The greeting</b> as it walks in (Identity.entered): an open, hospitable town greets it warmly and points it
 *     to the tavern; a middling one says good day; a closed one asks its business; a closed, raid-scarred one tells it
 *     to keep to the road. Where the watch alone goes armed, a guard at the gate looks at its weapons first. What the
 *     town already thinks of it (Standing) warms or cools all of it.</li>
 * <li><b>Trust</b> (Persona.feelFor, every warm word, gift and good turn): half again as fast in an open town (a
 *     quarter more again if it is Hospitable), two fifths slower in a closed one (less again if it is Raid-scarred);
 *     and a closed town holds a grudge a quarter longer.</li>
 * <li><b>The law</b> (LawBook): its weapons put away where the watch alone goes armed, its kills inside the town
 *     poaching where hunting is reserved, a house only for citizens (or nobody) where the law says so.</li>
 * <li><b>Leading it</b> (Government.standBarred): a citizen may stand where the town elects; an honoured guest before
 *     the elders or the masters; in a lordship only by marrying into the house or after a vote to depose it.</li>
 * </ul>
 */
public final class Treatment {

    private Treatment() {}

    /** How warm the town is to strangers, -100ish to 100ish: open or closed, its history with guests and raiders. */
    static int warmth(UUID village) {
        int w = Ethos.lean(village, Ethos.Axis.DOORS);
        if (TownTraits.has(village, TownTraits.Trait.HOSPITABLE)) w += 30;
        if (TownTraits.has(village, TownTraits.Trait.RAID_SCARRED)) w -= 30;
        if (LawBook.bordersClosed(village)) w -= 10;
        return w;
    }

    /**
     * A change in what a folk of this town thinks of a player (Persona.feelFor): warmer quicker in an open town, slower
     * in a closed one; a closed town's grudges cut a little deeper.
     */
    public static int trust(@Nullable UUID village, int delta) {
        if (delta == 0 || village == null || Identity.neutral() || Identity.known(village) == null) return delta;
        if (delta > 0) {
            double f = 1.0 + 0.5 * Ethos.plus(village, Ethos.Axis.DOORS) - 0.4 * Ethos.minus(village, Ethos.Axis.DOORS);
            if (TownTraits.has(village, TownTraits.Trait.HOSPITABLE)) f *= 1.25;
            if (TownTraits.has(village, TownTraits.Trait.RAID_SCARRED)) f *= 0.85;
            double x = delta * f;
            return Math.max(1, (int) (f >= 1.0 ? Math.ceil(x - 1e-9) : Math.round(x)));
        }
        double f = 1.0 + 0.25 * Ethos.minus(village, Ethos.Axis.DOORS);
        return (int) Math.floor(delta * f);
    }

    /** A guard near the player, else any grown folk awake near it, or null. */
    @Nullable
    static VillageFolkEntity nearestWatch(ServerLevel level, Player p, UUID village) {
        VillageFolkEntity guard = null, any = null;
        double g = 24.0 * 24.0, a = 24.0 * 24.0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(24.0),
                f -> f.isAlive() && !f.isSleeping() && !f.isBaby() && village.equals(f.ownerId()))) {
            double d = f.distanceToSqr(p);
            if (f.stationTask() == AssistantEntity.StationTask.GUARD && d < g) { g = d; guard = f; }
            if (d < a) { a = d; any = f; }
        }
        return guard != null ? guard : any;
    }

    /** The town's welcome, said by whoever is nearest (a guard at the gate first, where weapons are the watch's alone). */
    static void greet(ServerLevel level, Player p, UUID village, Identity.Rec r) {
        VillageFolkEntity by = nearestWatch(level, p, village);
        if (by == null) return;
        String town = Villages.name(village), you = p.getName().getString();
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (LawBook.watchAlone(village) && LawBook.weapon(p.getMainHandItem()) && by.stationTask() == AssistantEntity.StationTask.GUARD) {
            FolkTalk.speak(by, "Hold there, " + you + ". Weapons sheathed inside the walls — " + town + "'s law. Then you're welcome.");
            return;
        }
        int w = warmth(village) + (title.atLeast(Standing.Title.HONOURED) ? 60 : title.atLeast(Standing.Title.FRIEND) ? 30
            : title == Standing.Title.UNWELCOME || title == Standing.Title.OUTCAST ? -50 : 0);
        String said;
        if (w >= 40) said = FolkTalk.pick(by.getRandom(), "Welcome to " + town + ", " + you + "! Come in, come in — you'll find the tavern by the square.",
            you + "! Always a friend at " + town + "'s door. What can we do for you?", "Come in out of the road, friend — " + town + " is glad of company.");
        else if (w >= 10) said = FolkTalk.pick(by.getRandom(), "Good day, and welcome to " + town + ".", "Afternoon! Passing through " + town + "?");
        else if (w > -10) said = FolkTalk.pick(by.getRandom(), "Afternoon.", "Morning. Mind the carts.");
        else if (w > -40) said = FolkTalk.pick(by.getRandom(), "Hm. A stranger. What's your business in " + town + "?", "We don't get many visitors. State your business.");
        else said = FolkTalk.pick(by.getRandom(), "Keep to the road, stranger, and mind the watch. " + town + " remembers the last lot through that gate.",
            "Strangers aren't trusted here. Say what you want and be on your way.");
        FolkTalk.speak(by, said);
    }

    /** How the town treats a player, in a few lines, for the Identity page and /village identity. */
    static List<String> lines(UUID village, Identity.Rec r) {
        List<String> out = new ArrayList<>();
        int w = warmth(village);
        out.add("Greets strangers " + (w >= 40 ? "warmly" : w >= 10 ? "kindly" : w > -10 ? "civilly" : w > -40 ? "warily" : "coldly")
            + " (" + (w >= 0 ? "+" : "") + w + ").");
        int t = trust(village, 4);
        out.add("Trust: a kindness worth 4 elsewhere is worth " + t + " here" + (t > 4 ? ": an open town takes to players quickly." : t < 4
            ? ": a closed town takes its time over strangers." : "."));
        if (LawBook.watchAlone(village)) out.add("Weapons: the watch alone goes armed; a visitor's blade or bow is to be put away, or fined.");
        if (LawBook.huntingReserved(village)) out.add("Hunting: the town's game is its own hunters'; a visitor's kill inside it is poaching.");
        String houses = LawBook.housesBarred(village, false);
        out.add(houses == null ? "Houses: sold to any friend of the town." : "Houses: " + houses);
        out.add("Leading it: " + Government.routes(village, r));
        return out;
    }

    /** Tests: let a folk's opinions know which town it is of (VillageFolkEntity.aiStep does it once a second). */
    public static void attachForTests(VillageFolkEntity f) {
        f.persona().town = f.ownerId();
    }
}
