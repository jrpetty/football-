package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.FolkAction.Act;

/**
 * [anim] The player's emotes, and how a folk answers one (PlayerEmotes): which emotes a player has, how long each plays,
 * and the folk's answer in kind, by what it thinks of the player. Plain sums with no game in them, so a unit test can
 * walk every case.
 */
public final class EmoteRules {

    private EmoteRules() {}

    /** The emotes a player has, in the order the wheel shows them. */
    public static final Act[] EMOTES = {
        Act.WAVE, Act.BOW, Act.CHEER, Act.CLAP, Act.POINT, Act.BECKON, Act.SHRUG, Act.SALUTE, Act.HANDSHAKE, Act.DANCE,
        Act.SIT_GROUND, Act.KNEEL, Act.PRAY, Act.THINK,
    };

    public static boolean isEmote(Act a) {
        for (Act e : EMOTES) if (e == a) return true;
        return false;
    }

    /** How long an emote plays (ticks); the ones that go on (a dance, a sit, a prayer) until the player moves, or this. */
    public static int ticks(Act a) {
        return switch (a) {
            case WAVE, BECKON, HANDSHAKE -> 40;
            case BOW -> 44;
            case CHEER -> 36;
            case CLAP -> 60;
            case POINT -> 36;
            case SHRUG -> 30;
            case SALUTE -> 32;
            case THINK -> 120;
            case DANCE, SIT_GROUND, KNEEL, PRAY -> 400;
            default -> 40;
        };
    }

    /** One emote a second, and no more than five in ten seconds. */
    public static final int GAP = 20, BURST = 5, BURST_WINDOW = 200;

    /** What the folk knows of the player that bears on its answer. */
    public record Who(int affinity, boolean hired, boolean honoured, boolean guard, boolean atAGathering, boolean facedByIt) {}

    /**
     * A folk's answer to a player's emote: the act it plays back, or NONE (it carries on). A wave back from a folk that
     * likes the player; a bow back to one it honours (its leader, a knight of its town) or thinks the world of; a cheer
     * along at a gathering; a dance joined at a feast; a beckon followed by its own hired folk or a close friend; a salute
     * returned by the watch; a handshake taken, if the player is not somebody it cannot stand (a shrug if so), and only
     * by the one it was offered to.
     */
    public static Act answer(Act emote, Who w) {
        int a = w.affinity();
        return switch (emote) {
            case WAVE -> a >= 10 || w.hired() ? Act.WAVE : Act.NONE;
            case BOW -> w.honoured() || a >= 60 ? Act.BOW : a >= 20 ? Act.WAVE : Act.NONE;
            case CHEER -> w.atAGathering() || a >= 30 ? Act.CHEER : a >= 5 ? Act.CLAP : Act.NONE;
            case CLAP -> a >= 0 && (w.atAGathering() || a >= 15) ? Act.CLAP : Act.NONE;
            case DANCE -> w.atAGathering() || a >= 40 ? Act.DANCE : a >= 15 ? Act.CLAP : Act.NONE;
            case BECKON -> w.hired() || a >= 50 ? Act.WAVE : w.facedByIt() ? Act.SHRUG : Act.NONE;
            case POINT -> w.hired() ? Act.SALUTE : Act.NONE;
            case SALUTE -> w.guard() && (a >= 0 || w.honoured()) ? Act.SALUTE : a >= 25 ? Act.WAVE : Act.NONE;
            case HANDSHAKE -> !w.facedByIt() ? Act.NONE : a >= -20 ? Act.HANDSHAKE : Act.SHRUG;
            case SHRUG -> w.facedByIt() && a >= 0 ? Act.SHRUG : Act.NONE;
            case THINK -> w.facedByIt() && a >= 0 ? Act.THINK : Act.NONE;
            case PRAY, KNEEL -> a >= 30 && !w.guard() ? Act.PRAY : Act.NONE;
            default -> Act.NONE;
        };
    }

    /** Does the folk follow the player for this (a beckon to its hired folk or a close friend)? */
    public static boolean follows(Act emote, Who w) {
        return emote == Act.BECKON && (w.hired() || w.affinity() >= 50);
    }

    /** Does it walk to where the player points (its hired folk)? */
    public static boolean goesWherePointed(Act emote, Who w) {
        return emote == Act.POINT && w.hired();
    }
}
