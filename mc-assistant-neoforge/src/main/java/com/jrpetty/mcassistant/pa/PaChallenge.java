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
        /** [pa33e] Its mode before a run (it runs on STAY, so following you doesn't tug it back to the line). */
        @Nullable com.jrpetty.mcassistant.entity.AssistantEntity.Mode before;
        String scoreShown = "";
        int mineShots;
        /** [pa33g] When its arrows that will hit land (the point counts then). */
        final java.util.List<Integer> hitsDue = new java.util.ArrayList<>();

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

    /** How far across the ground (a tree's trunk counts as reached from beside it). */
    static double flat(net.minecraft.world.phys.Vec3 at, BlockPos goal) {
        return Math.hypot(at.x - (goal.getX() + 0.5), at.z - (goal.getZ() + 0.5)) - 0.5;
    }

    /** Its speed in a race: [pa35] a player's sprint (thirty per cent over a walk) at every level; a higher level picks a better line, not faster legs. */
    public static double raceSpeed(int level) {
        return 1.3;
    }

    static String start(ServerPlayer p, PlayerAssistantEntity a, String kind, PaOrders.Look look) {
        if (GAMES.containsKey(p.getUUID())) return PaTalk.line(a, "challenge_busy");
        long now = a.level().getGameTime();
        switch (kind) {
            case "race" -> {
                BlockPos goal = look.block();
                if (goal == null || goal.distSqr(p.blockPosition()) < 6 * 6) return "Point at somewhere a fair way off (or ping it) and say \"race me to that\".";
                double dx = goal.getX() + 0.5 - p.getX(), dz = goal.getZ() + 0.5 - p.getZ(), len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                a.teleportTo(p.getX() - dz / len * 1.6, p.getY(), p.getZ() + dx / len * 1.6);   // to the line, at your shoulder (not in your way)
                a.setYRot((float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90F);
                a.setYHeadRot(a.getYRot());
                Game race = new Game("race", goal.immutable(), now + COUNT, now + COUNT + 20 * 90, 0, 0);
                race.before = a.getMode();
                a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);   // [pa33e] off the lead for the run: following you would walk it back to the line
                GAMES.put(p.getUUID(), race);
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
                if (a.countMatching(s -> s.is(net.minecraft.world.item.Items.ARROW)) < 5 || !PaShots.hasBow(a)) return PaTalk.line(a, "need_bow");
                Game shoot = new Game("shoot", target.immutable(), now + COUNT, now + COUNT + 20 * 60, 0, 0);
                shoot.before = a.getMode();
                a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);   // [pa33g] it stands and shoots: walking spoils an aim
                a.getNavigation().stop();
                GAMES.put(p.getUUID(), shoot);
                a.plan.paused = true;
                return PaTalk.line(a, "challenge_shoot");
            }
            case "xbow" -> {                                            // [pa35] the crossbow contest: ten bolts each, spanned and loosed as a player's
                BlockPos target = look.block();
                if (target == null || !a.level().getBlockState(target).is(net.minecraft.world.level.block.Blocks.TARGET))
                    return "Point at a target block (or ping one) and say \"crossbow contest\". Ten bolts each.";
                if (!a.mayShoot(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW)) && a.countMatching(s -> s.is(net.minecraft.world.item.Items.CROSSBOW)) + (a.getMainHandItem().is(net.minecraft.world.item.Items.CROSSBOW) ? 1 : 0) > 0)
                    return PaTalk.line(a, "gear.locked", "item", "crossbow", "level", String.valueOf(PaLevels.gearLevel(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW))));
                if (a.countMatching(s -> s.is(net.minecraft.world.item.Items.ARROW)) < 5 || !PaCrossbow.has(a)) return PaTalk.line(a, "need_crossbow");
                Game shoot = new Game("xbow", target.immutable(), now + COUNT, now + COUNT + 20 * 70, 0, 0);
                shoot.before = a.getMode();
                a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);
                a.getNavigation().stop();
                GAMES.put(p.getUUID(), shoot);
                a.plan.paused = true;
                return PaTalk.line(a, "challenge_xbow");
            }
            default -> { return "I don't know that game. Race, mining, shooting or crossbows."; }
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
                int w = raceWinner(flat(p.position(), g.goal), flat(a.position(), g.goal));
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
                int due = (int) Math.min(10, (now - g.start) / 40 + 1);         // [pa33e] a shot every two seconds, counted (not on a tick it might never see)
                // [pa33g] each of its ten drawn as a player's: bow up, head round onto the target, a full pull, the loose
                if (g.mineShots < due && !PaBow.drawing(a)) {
                    g.mineShots++;
                    PaShots.shootAt(a, g.goal, shot -> { if (shot.willHit()) g.hitsDue.add(a.tickCount + shot.flightTicks()); });
                }
                for (java.util.Iterator<Integer> it = g.hitsDue.iterator(); it.hasNext(); ) if (it.next() <= a.tickCount) { it.remove(); g.mineScore++; }   // counted as it lands
                String score = "You " + g.ownerScore + ", " + a.displayNameCap() + " " + g.mineScore;   // [pa33e] the score as it goes
                if (!score.equals(g.scoreShown)) { g.scoreShown = score; PaNet.note(p, score); }
                if (now > g.end || now > g.start + 360 + 100) finish(a, p, g, Integer.compare(g.ownerScore, g.mineScore));   // its ten shots, then a moment for your last
            }
            case "xbow" -> {                                            // [pa35] a bolt every two and a half seconds: span, shoulder, loose
                int due = (int) Math.min(10, (now - g.start) / 50 + 1);
                if (g.mineShots < due && !PaCrossbow.busy(a)) {
                    g.mineShots++;
                    PaCrossbow.atBlock(a, g.goal, shot -> { if (shot.willHit()) g.hitsDue.add(a.tickCount + shot.flightTicks()); });
                }
                for (java.util.Iterator<Integer> it = g.hitsDue.iterator(); it.hasNext(); ) if (it.next() <= a.tickCount) { it.remove(); g.mineScore++; }
                String score = "You " + g.ownerScore + ", " + a.displayNameCap() + " " + g.mineScore;
                if (!score.equals(g.scoreShown)) { g.scoreShown = score; PaNet.note(p, score); }
                if (now > g.end || now > g.start + 500 + 100) finish(a, p, g, Integer.compare(g.ownerScore, g.mineScore));
            }
            default -> GAMES.remove(p.getUUID());
        }
    }

    /** A target block hit by its player's arrow (from the server's projectile event). */
    public static void ownerHit(ServerPlayer p, BlockPos at) {
        Game g = GAMES.get(p.getUUID());
        if (g != null && (g.kind.equals("shoot") || g.kind.equals("xbow")) && !g.over && at.equals(g.goal)) g.ownerScore++;
    }

    /** It's over: who won (1 its player, -1 it, 0 a draw or nobody), the score, a line in kind, pay for taking part. */
    static void finish(PlayerAssistantEntity a, ServerPlayer p, Game g, int result) {
        g.over = true;
        GAMES.remove(p.getUUID());
        if (g.kind.equals("shoot")) PaBow.cancel(a);
        if (g.kind.equals("xbow")) PaCrossbow.cancel(a);
        a.plan.paused = false;
        a.setSprinting(false);
        a.getNavigation().stop();
        if (g.before != null) a.setMode(g.before);
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
