package com.jrpetty.mcassistant.entity;

import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A wave and a hello. [townlife]
 *
 * <p>A folk who knows a player — has met them for itself, not only heard of them from the others —
 * greets them by name the first time each day they come within a few steps: it turns to them, lifts
 * a hand and calls out ("Morning, Alex!"). Once a day each, so a player walking through the square
 * at noon is waved at by the folk who know them, and only once. Somebody it cannot abide, or an
 * outcast, gets no wave (VillageFolkEntity.greetPassersBy has its own words for them).
 *
 * <p>A child who likes the player tags along after the wave for half a minute or so, keeping a couple
 * of steps behind, chattering, and goes back to its own day when the time is up, the player gets too
 * far ahead, or it is called away to bed.
 */
public final class Greetings {

    private Greetings() {}

    /** How much a child must like a player to tag along after them. */
    static final int LIKES = 20;
    /** How long a child tags along (thirty seconds). */
    static final int FOLLOW = 600;
    /** A player further off than this has left the child behind. */
    static final double LOST = 24.0;

    /** The day each folk last waved to each player: folk, then player. */
    private static final Map<UUID, Map<UUID, Long>> WAVED = new ConcurrentHashMap<>();

    /** A child tagging along after a player: whom, till when. */
    static final class Tag {
        final Player player;
        final int until;
        int walkTick = -1000;

        Tag(Player player, int until) {
            this.player = player;
            this.until = until;
        }
    }

    private static final Map<UUID, Tag> TAGS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WAVED.clear();
        TAGS.clear();
    }

    /**
     * A player come within a few steps (VillageFolkEntity.greetPassersBy): if this folk knows them and has
     * not waved to them today, a wave and a hello by name; a child who likes them falls in behind. True if it waved.
     */
    static boolean wave(VillageFolkEntity f, Player p) {
        if (f.isSleeping() || p.isSpectator() || p.isInvisible()) return false;
        f.ensurePersona();
        UUID who = p.getUUID();
        Persona me = f.persona();
        if (!me.knows(who)) return false;
        int aff = me.affinity(who);
        if (aff <= -15) return false;                              // a cold look is not a wave
        long day = Math.floorDiv(f.level().getDayTime(), 24000L);
        Map<UUID, Long> mine = WAVED.computeIfAbsent(f.getUUID(), k -> new ConcurrentHashMap<>());
        Long last = mine.get(who);
        if (last != null && last == day) return false;
        UUID village = f.ownerId();
        if (village != null && Standing.of(village, who, f.level().getGameTime()).title() == Standing.Title.OUTCAST) return false;
        mine.put(who, day);
        if (WAVED.size() > 4096) WAVED.clear();                     // a long game's worth of folk: start the book afresh
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, hello(f, p, aff));
        if (f.isBaby() && aff >= LIKES && !f.isSleeping()) {
            TAGS.put(f.getUUID(), new Tag(p, f.tickCount + FOLLOW));
            if (TAGS.size() > 256) TAGS.values().removeIf(t -> t.player.isRemoved());
        }
        return true;
    }

    /** What it calls out: the player's name, said its own way. */
    static String hello(VillageFolkEntity f, Player p, int aff) {
        RandomSource r = f.getRandom();
        String you = p.getName().getString();
        String when = timeOfDay(f);
        if (f.isBaby()) {
            return aff >= LIKES ? FolkTalk.pick(r, you + "! " + you + "! Wait for me!", "Hi, " + you + "! Where are you going?",
                "It's " + you + "! Can I come too?") : FolkTalk.pick(r, "Hello, " + you + "!", "Hi, " + you + ".");
        }
        if (f.life().has(Social.Trait.GRUMPY) && aff < 30) return FolkTalk.pick(r, you + ".", when + ", " + you + ".");
        if (f.life().has(Social.Trait.SHY) && aff < 30) return FolkTalk.pick(r, "Oh — h-hello, " + you + ".", "…" + when + ", " + you + ".");
        if (aff >= 55) return FolkTalk.pick(r, you + "! Good to see you!", "There you are, " + you + "!", when + ", " + you + "! Lovely to see you.");
        return FolkTalk.pick(r, when + ", " + you + "!", "Hello, " + you + "!", you + " — hello!");
    }

    private static String timeOfDay(VillageFolkEntity f) {
        long t = Math.floorMod(f.level().getDayTime(), 24000L);
        return t < 4000L || t >= 23000L ? "Morning" : t < 10000L ? "Afternoon" : "Evening";
    }

    /**
     * A child tagging along (every tick, VillageFolkEntity.aiStep): a couple of steps behind the player,
     * looking up at them, a word now and then; done when the time is up or the player has gone on.
     * True while it is about it (the rest of its day waits).
     */
    static boolean tagAlong(VillageFolkEntity child) {
        Tag t = TAGS.get(child.getUUID());
        if (t == null) return false;
        Player p = t.player;
        boolean over = child.tickCount > t.until || !child.isBaby() || child.isSleeping() || !p.isAlive() || p.isRemoved()
            || p.level() != child.level() || child.distanceToSqr(p) > LOST * LOST || child.level().isNight();
        if (over) {
            TAGS.remove(child.getUUID(), t);
            child.getNavigation().stop();
            if (child.distanceToSqr(p) <= 8.0 * 8.0 && child.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Bye, " + p.getName().getString() + "!", "I have to go home now.",
                    "See you tomorrow!"));
            }
            return false;
        }
        child.lastLeisureTick = child.tickCount;
        child.hobbyNow = "tagging along after " + p.getName().getString();
        if (child.tickCount % 10 != 0) return true;
        double d = child.distanceToSqr(p);
        if (d > 3.0 * 3.0) {
            if (child.getNavigation().isDone() || child.tickCount - t.walkTick > 20) {
                child.walkTo(p.blockPosition(), 1.1D);
                t.walkTick = child.tickCount;
            }
            if (d > 7.0 * 7.0 && child.getRandom().nextInt(10) == 0) FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Wait for me!", "Not so fast!"));
        } else {
            child.getNavigation().stop();
            child.getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (child.getRandom().nextInt(16) == 0) {
                FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Where are we going?", "Is that a real sword?", "Can I carry something?",
                    "When I grow up I'm going to be just like you.", "Look, a butterfly!"));
            }
        }
        return true;
    }

    /** Who this child is tagging along after, or null. */
    @Nullable
    public static UUID following(VillageFolkEntity child) {
        Tag t = TAGS.get(child.getUUID());
        return t == null ? null : t.player.getUUID();
    }

    /** For the tests: a player come within a few steps of this folk, now. True if it waved. */
    public static boolean waveForTests(VillageFolkEntity f, Player p) {
        return wave(f, p);
    }
}
