package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [pa33d] Challenges with its player: a race to a spot ("race me to that tree"), a minute's mining ("who can mine more
 * in a minute"), and a shooting contest at a target ("shooting contest"). It plays fair at its level (its own legs,
 * its own pickaxe and its own aim), counts down, keeps score, and trash-talks within its Language setting.
 */
public final class PaChallenge {

    private PaChallenge() {}

    /** One challenge running: what, where, when it began and ends, and the scores. */
    static final class Game {
        final String kind;
        @Nullable final BlockPos goal;
        final long start, end;
        final int ownerBase, mineBase;
        int ownerScore, mineScore, shown = -1;
        boolean over, went;

        Game(String kind, @Nullable BlockPos goal, long start, long end, int ownerBase, int mineBase) {
            this.kind = kind;
            this.goal = goal;
            this.start = start;
            this.end = end;
            this.ownerBase = ownerBase;
            this.mineBase = mineBase;
        }
    }

    static final Map<UUID, Game> GAMES = new ConcurrentHashMap<>();
    /** Countdown before it starts (ticks). */
    static final int COUNT = 60;

    /** Who won a race, from the two distances to the goal (blocks) when one of them got there: 1 its player, -1 it, 0 not yet. */
    public static int raceWinner(double ownerDist, double mineDist) {
        boolean o = ownerDist <= 1.8, m = mineDist <= 1.8;
        if (o && m) return ownerDist <= mineDist ? 1 : -1;
        return o ? 1 : m ? -1 : 0;
    }

    /** Its walking speed in a race, from its level (it sprints like a player; a better runner each level). */
    public static double raceSpeed(int level) {
        return 1.15 + 0.035 * (Math.max(1, Math.min(10, level)) - 1);
    }

    static String start(ServerPlayer p, PlayerAssistantEntity a, String kind, PaOrders.Look look) {
        if (GAMES.containsKey(p.getUUID())) return PaTalk.line(a, "challenge_busy");
        long now = a.level().getGameTime();
        switch (kind) {
            case "race" -> {
                BlockPos goal = look.block();
                if (goal == null || goal.distSqr(p.blockPosition()) < 6 * 6) return "Point at somewhere a fair way off (or ping it) and say \"race me to that\".";
                a.teleportTo(p.getX() + 1.0, p.getY(), p.getZ());    // to the line, beside you
                GAMES.put(p.getUUID(), new Game("race", goal.immutable(), now + COUNT, now + COUNT + 20 * 90, 0, 0));
                PaNet.ping(p, goal, "Finish", "MOVE", "found");
                a.plan.paused = true;
                a.clearQueue();
                return PaTalk.line(a, "challenge_race");
            }
            case "mine" -> {
                int ownerMined = mined(p);
                GAMES.put(p.getUUID(), new Game("mine", null, now + COUNT, now + COUNT + 20 * 60, ownerMined, a.deedCount(com.jrpetty.mcassistant.entity.AssistantEntity.Deed.BLOCKS_MINED)));
                a.plan.paused = true;
                return PaTalk.line(a, "challenge_mine");
            }
            case "shoot" -> {
                BlockPos target = look.block();
                if (target == null || !a.level().getBlockState(target).is(net.minecraft.world.level.block.Blocks.TARGET))
                    return "Point at a target block (or ping one) and say \"shooting contest\". Ten shots each.";
                if (a.countMatching(s -> s.is(net.minecraft.world.item.Items.ARROW)) < 5 || !a.hasBow()) return PaTalk.line(a, "need_bow");
                GAMES.put(p.getUUID(), new Game("shoot", target.immutable(), now + COUNT, now + COUNT + 20 * 60, 0, 0));
                a.plan.paused = true;
                return PaTalk.line(a, "challenge_shoot");
            }
            default -> { return "I don't know that game. Race, mining or shooting."; }
        }
    }

    /** Blocks its player has mined, all told (the game's own count). */
    static int mined(ServerPlayer p) {
        int n = 0;
        for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) n += p.getStats().getValue(Stats.BLOCK_MINED.get(b));
        return n;
    }

    /** Every few ticks from its brain: the countdown, the game, the result. */
    static void tick(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer p)) return;
        Game g = GAMES.get(p.getUUID());
        if (g == null) return;
        long now = a.level().getGameTime();
        if (now < g.start) {
            int left = (int) ((g.start - now + 19) / 20);
            if (left != g.shown) { g.shown = left; PaNet.note(p, left + "..."); a.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F); }
            return;
        }
        if (!g.went) {
            g.went = true;
            PaNet.note(p, "Go.");
            a.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.4F);
            PaTalk.reply(a, PaTalk.line(a, "challenge_go"));
        }
        switch (g.kind) {
            case "race" -> {
                if (a.getNavigation().isDone() || now % 20 == 0) a.getNavigation().moveTo(g.goal.getX() + 0.5, g.goal.getY(), g.goal.getZ() + 0.5, raceSpeed(a.veteranLevel()));
                a.setSprinting(true);
                int w = raceWinner(Math.sqrt(p.blockPosition().distSqr(g.goal)), Math.sqrt(a.blockPosition().distSqr(g.goal)));
                if (w != 0) finish(a, p, g, w > 0 ? 1 : -1);
                else if (now > g.end) finish(a, p, g, 0);
            }
            case "mine" -> {
                g.ownerScore = mined(p) - g.ownerBase;
                g.mineScore = a.deedCount(com.jrpetty.mcassistant.entity.AssistantEntity.Deed.BLOCKS_MINED) - g.mineBase;
                if (a.task == null && a.peekJob() == null) a.enqueue(com.jrpetty.mcassistant.entity.Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE, 64));
                if (now > g.end) {
                    a.clearQueue();
                    finish(a, p, g, Integer.compare(g.ownerScore, g.mineScore));
                }
            }
            case "shoot" -> {
                if ((now - g.start) % 40 == 0 && g.mineScore + g.ownerScore < 99 && (now - g.start) / 40 < 10) {
                    a.getLookControl().setLookAt(g.goal.getX() + 0.5, g.goal.getY() + 0.5, g.goal.getZ() + 0.5);
                    if (PaShots.shootAt(a, g.goal)) g.mineScore++;
                }
                if (now > g.end) finish(a, p, g, Integer.compare(g.ownerScore, g.mineScore));
            }
            default -> GAMES.remove(p.getUUID());
        }
    }

    /** A target block hit by its player's arrow (from the server's projectile event). */
    public static void ownerHit(ServerPlayer p, BlockPos at) {
        Game g = GAMES.get(p.getUUID());
        if (g != null && g.kind.equals("shoot") && !g.over && at.equals(g.goal)) g.ownerScore++;
    }

    /** It's over: who won (1 its player, -1 it, 0 a draw or nobody), the score, a line in kind, pay for taking part. */
    static void finish(PlayerAssistantEntity a, ServerPlayer p, Game g, int result) {
        g.over = true;
        GAMES.remove(p.getUUID());
        a.plan.paused = false;
        a.setSprinting(false);
        a.getNavigation().stop();
        String score = g.kind.equals("race") ? "" : " (" + g.ownerScore + " to " + g.mineScore + ")";
        String key = result > 0 ? "challenge_lost" : result < 0 ? "challenge_won" : "challenge_draw";
        PaData.Rec r = a.rec();
        int lang = r == null || r.mute > 0 ? 0 : PaTone.level(r.language, PaConfig.maxLanguage());   // trash talk only as strong as it's allowed
        a.playAnim(result < 0 ? "cheer" : result > 0 ? "shrug" : "think");
        String said = null;
        if (result != 0 && lang > 0) said = PaBanter.toneLine(a, "tone.challenge." + (result < 0 ? "won" : "lost") + "." + (lang >= 2 ? "unfiltered" : "cheeky"), score.trim());
        PaTalk.reply(a, (said != null ? said : PaTalk.line(a, key, "score", score.trim())) + score);
        a.earn("challenge");
    }
}
