package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [pa33] The Player AI Assistant's own thinking, on top of the engine's: it feeds its plan to the engine a step at a
 * time and watches each one (progress, an honest failure with a fix, done), keeps the plate and the HUD line saying
 * what it is doing, talks like a companion (night falling, its player hurt, diamonds), gets itself out of trouble
 * (stuck, in lava, drowning: back to its player), keeps to its guard post, and, with nothing to do and auto-help on,
 * does something useful on its own and says what and why, which its player can veto.
 *
 * <p>Kept to a tick budget (PaConfig.budgetMicros) shared by every assistant on the server; the time it takes is
 * measured ({@link #microsPerTick}).
 */
public final class PaBrain {

    private PaBrain() {}

    /** The brain's scratch, per assistant. */
    public static final class Mind {
        public int lastSaid = -100000, lastComment = -100000;
        public final PaTone.Heat toneHeat = new PaTone.Heat();          // [pa33b] its player's recent tone
        public final PaStrangers.Limit strangers = new PaStrangers.Limit();   // [pa33b] other players: rate and heat, per player
        @Nullable public BlockPos post;
        @Nullable public BlockPos roamCentre;
        long nightWarnedDay = -1;
        /** [pa33d] Told to go to bed (till morning); when it last talked in its sleep; was its player asleep. */
        public boolean bedtime, ownerWasAsleep;
        public int sleepTalkAt;
        /** [pa33d] Moves: the shield up (till when), the clutch's water (and when), the last pearl and how stuck it is. */
        public boolean blocking;
        public int blockUntil, clutchTick, pearlAt = -100000, pearlStuck;
        public double pearlDist;
        @Nullable public BlockPos clutchAt;
        /** [pa33d] A bare ping it glances at, till when (a turn of the head, nothing more). */
        @Nullable public BlockPos glanceAt;
        public int glanceUntil, endSaidAt = -100000;
        /** [pa33e] Elytra following: wings on (by it), when it took off, its last rocket and word. */
        public boolean wingsOn;
        /** [pa33e] Ticks running it was held back by the shared budget. */
        public int starved;
        /** [pa33f] When it last told a joke (a laugh soon after is at it). */
        public int jokeAt = -100000;
        /** [pa33f] What it's aiming at, and till when (its eyes stay there). */
        @Nullable public net.minecraft.world.entity.Entity aimEntity;
        @Nullable public net.minecraft.world.phys.Vec3 aimSpot;
        public int aimUntil;
        /** [pa35] A block it is breaking (PaPace: the cracks a player's digging shows), or null. */
        @Nullable public PaPace.Dig dig;
        /** [pa33g] A bow drawn (PaBow), or null. */
        @Nullable public PaBow.Draw bow;
        /** [pa33g] A come-here under way (PaCome), or null. */
        @Nullable public PaCome.Trip come;
        /** [pa33g] Its window of awake chunks (PaChunks), or null. */
        @Nullable public PaChunks.Held chunks;
        public int takeoffAt, rocketAt = -1000, flightSaidAt = -100000, wingsAt = -1000;
        /** [pa33e] Parkour: a jump in the air (from, to, the gap), a gap it fell short at, a bridge or pillar under way. */
        public int leapAt, pkStuck, pkMode, pkLaid, pkRestUntil, pkSaidAt = -100000;
        public double pkDist;
        @Nullable public BlockPos leapFrom, leapTo, pkJump;
        @Nullable PaParkour.Gap leapGap, failedGap, pkBridge;
        int ownerHurtTick = -100000, meHurtTick = -100000, lastTip, lastAuto, stuckFor, sortedAt = -100000;
        int lastDiamonds = -1, threatTick = -100000;
        @Nullable Vec3 lastPos;
        public final Set<String> vetoed = new HashSet<>();
        int lastPct = -1, sameFor;
        /** Beats left of climbing out of a hole (PaClimb). */
        public int climbing;
        int tips;
        /** When to open its player's panel after it first arrives (0: not pending). */
        public int openPanelAt;
        int askedFoodTick = -100000;
        /** [pa33b] Lines said lately (a long memory: no repeats within a long session). */
        public final PaPersona.Recent recent = new PaPersona.Recent(400);
        /** [pa33b] Its player's last death: where, which world, how, when (game time). */
        @Nullable public BlockPos deathPos;
        @Nullable public BlockPos danger;                         // [pa33c-2b] a spot its player marked as dangerous
        public String deathDim = "", deathCause = "";
        public long deathAt = -1, prevDeathAt = -1;
        public int deathsRecent, respawnQuipAt, lastJoke = -100000, lastHit = -100000, slowQuipped, jmNext;
        public boolean askedTwice;
        public boolean wasRaining, sawDirt;
        @Nullable public java.util.UUID lastTown;
        public String lastOrder = "";
        public int lastOrderAt = -100000, ownerStillFor;
        @Nullable public net.minecraft.world.phys.Vec3 ownerLast;
    }

    // ------------------------------------------------------------------ the budget

    private static long tickStamp = -1, spentNanos, totalNanos, ticksSeen, overBudget;

    /** Average microseconds the assistants' own thinking took per server tick (all of them together). */
    public static double microsPerTick() {
        return ticksSeen == 0 ? 0 : totalNanos / 1000.0 / ticksSeen;
    }

    public static long overBudgetTicks() {
        return overBudget;
    }

    public static void resetStats() {
        totalNanos = 0;
        ticksSeen = 0;
        overBudget = 0;
    }

    public static void tick(PlayerAssistantEntity a) {
        long gt = a.level().getGameTime();
        if (gt != tickStamp) {
            if (tickStamp >= 0) { totalNanos += spentNanos; ticksSeen++; if (spentNanos > PaConfig.budgetMicros() * 1000L) overBudget++; }
            tickStamp = gt;
            spentNanos = 0;
        }
        boolean spare = spentNanos < PaConfig.budgetMicros() * 1000L;
        long t0 = System.nanoTime();
        try {
            think(a, spare);
        } finally {
            spentNanos += System.nanoTime() - t0;
        }
    }

    private static void think(PlayerAssistantEntity a, boolean spare) {
        int t = a.tickCount + a.getId();
        if (t % 20 == 0 && !valid(a)) return;
        if (t % 5 == 0) {
            if (a.mind.climbing > 0) PaClimb.tick(a);              // out of the hole first, then on with the plan
            else feed(a);                                         // never skipped: stop and orders must be prompt
        }
        PaMoves.tick(a);                                          // [pa33d] shield, clutch, sprint, pearl: never held back by the budget
        if (t % 10 == 3) PaSleep.tick(a);                         // [pa33d] bedtime; [pa33e] cheap, so never held back either
        if (t % 2 == 0) PaChallenge.tick(a);                      // [pa33d] a race, a mining minute, a shooting match (timed: never held back)
        // [pa33e] the budget is shared by every assistant on the server, so the last in the tick order could go without for
        // ever: one held back four ticks running thinks on the fifth regardless
        if (!spare && ++a.mind.starved < 5) return;
        a.mind.starved = 0;
        if (t % 10 == 0) state(a);
        if (a.mind.openPanelAt > 0 && a.tickCount >= a.mind.openPanelAt) {
            a.mind.openPanelAt = 0;
            if (a.getOwnerPlayer() instanceof ServerPlayer owner) PaNet.open(owner, a, "talk");
        }
        if (t % 20 == 0) personalSpace(a);
        if (t % 20 == 0) banter(a);                               // [pa33b] remarks, always after anything useful
        if (t % 20 == 5) sleptIn(a);                              // [pa33c-2b] the bed it sleeps in is its spawn
        if (t % 20 == 7) PaMemoryAct.tick(a);                     // [pa35] its standing rules, friends, foes, stock
        if (a.mind.glanceAt != null) {                            // [pa33d] a look at a bare ping
            if (a.tickCount < a.mind.glanceUntil) a.getLookControl().setLookAt(a.mind.glanceAt.getX() + 0.5, a.mind.glanceAt.getY() + 0.5, a.mind.glanceAt.getZ() + 0.5);
            else a.mind.glanceAt = null;
        }
        if (t % 10 == 0) boat(a);                                 // [pa33b] along with its player on the water
        if (t % 40 == 0) {
            safety(a);
            companion(a);
            post(a);
        }
        if (t % 40 == 20) autoHelp(a);                            // [pa35] asked more often; how often it acts grows with its level (PaGrowth)
    }

    /** Still the real one? A dismissed assistant, or an old copy left in a chunk, goes. */
    private static boolean valid(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || r.incarnation != a.incarnation() || r.away || r.dead(a.level().getGameTime())) {
            a.discard();
            return false;
        }
        if (r.entity == null || !r.entity.equals(a.getUUID())) { r.entity = a.getUUID(); PaData.touch(); }
        return true;
    }

    // ------------------------------------------------------------------ the plan, a step at a time

    public static void feed(PlayerAssistantEntity a) {
        PaPlan plan = a.plan;
        if (plan.paused) return;
        PaPlan.Step cur = plan.current;
        long now = a.level().getGameTime();
        if (cur == null) {
            if (plan.steps.isEmpty()) return;
            cur = plan.steps.remove(0);
            plan.current = cur;
            cur.started = now;
            cur.fail = "";
            if (!cur.keepBase || cur.base < 0) cur.base = cur.count.isEmpty() ? -1 : PaItems.count(a, cur.count);
            cur.keepBase = false;
            a.mind.roamCentre = cur.centre != null ? cur.centre : a.blockPosition();
            a.mind.lastPct = -1;
            a.mind.sameFor = 0;
            for (Job j : cur.jobs) a.enqueue(j);
            if (cur.task != null) {
                cur.task.stage = 0;
                cur.task.progress = 0;
                cur.task.failed = false;
                cur.task.result = "";
                a.task = cur.task;
            }
            cur.fed = true;
            return;
        }
        boolean running = a.jobCount() > 0 || (cur.task != null && a.task == cur.task);
        int pct = cur.percent(a);
        cur.best = Math.max(cur.best, pct);                                // [pa33d] logs handed over mid-way still count
        if (!running) pct = Math.max(pct, cur.best);
        // like a player, it stops when it has what it was sent for (a dig "until you have 20", or 16 logs)
        if ((cur.stopAtTarget || "GATHER".equals(cur.kind)) && cur.target > 0 && pct >= 100 && running) {
            a.clearQueue();
            a.task = null;
            running = false;
        }
        if (running) {
            long limit = Math.max(600, cur.eta * 5L) * 20L;
            if (now - cur.started > limit) {
                cur.fail = "That was taking far too long, so I stopped.";
                a.clearQueue();
                a.task = null;
            } else return;
        }
        // finished, one way or another
        plan.current = null;
        if (cur.target > 0 && !cur.count.isEmpty() && pct >= 100) cur.fail = "";          // it has what it was sent for: whatever was said on the way
        if (cur.task != null && cur.task.failed && cur.fail.isEmpty()) cur.fail = cur.task.result;
        if (cur.fail.isEmpty() && cur.target > 0 && !cur.count.isEmpty() && pct >= 0 && pct < 60 && !cur.stopAtTarget) {
            cur.fail = "I only managed " + cur.tally(a) + " " + cur.count + "; there's no more I can reach round here.";
        }
        if (cur.fail.isEmpty() && cur.stopAtTarget && cur.target > 0 && pct >= 0 && pct < 100) {
            cur.fail = "I found " + cur.tally(a) + " " + cur.count + " before the mine ran out.";
        }
        PaData.Rec r = a.rec();
        if (!cur.fail.isEmpty() && (retool(a, cur) || unstick(a, cur) || farther(a, cur))) return;
        if (!cur.fail.isEmpty() && cur.optional && plan.steps.stream().anyMatch(s -> !s.auto)) {
            // a finishing touch it can't make: it says so plainly and gets on with the rest
            PaTalk.reply(a, "I couldn't " + cur.label + ": " + lower(trimEnd(shortReason(cur.fail))) + " I'll do without it.");
            return;
        }
        if (!cur.fail.isEmpty()) {
            boolean more = plan.steps.stream().anyMatch(s -> !s.auto);
            if (cur.auto) { plan.dropAuto(); return; }
            plan.failed = cur;
            List<Choice> fixes = PaOrders.fixesFor(cur.fail, "#retry");
            if (more) {
                plan.paused = true;
                fixes = new java.util.ArrayList<>(fixes);
                fixes.add(0, new Choice("Skip it, carry on", "resume"));
            }
            PaTalk.reply(a, PaTalk.line(a, "cant", "what", trimEnd(cur.fail)), fixes);
            return;
        }
        if (r != null && !cur.auto) { r.jobsDone++; PaData.touch(); }
        a.earn("BUILD".equals(cur.kind) ? "build" : cur.auto ? "craft" : "job");   // [pa33d] paid for the job done
        String res = cur.task != null && !cur.task.result.isEmpty() ? cur.task.result : "";
        boolean last = plan.steps.stream().noneMatch(s -> !s.auto);
        if (cur.auto) {
            if (!res.isEmpty()) PaTalk.comment(a, 2, res);
            return;
        }
        String line = PaTalk.line(a, last ? "done" : "step", "did", PaPhrases.did(cur.label), "job", cur.label);
        if (!res.isEmpty()) line = res + " " + (last ? "That's everything." : "");
        if (last) PaTalk.reply(a, line.trim());
        else PaTalk.comment(a, 1, line.trim());
    }

    private static final java.util.regex.Pattern NO_PICK = java.util.regex.Pattern.compile("(?i)(without a pickaxe|no pickaxe|need a pickaxe|pickaxe broke)");

    /**
     * Its pickaxe wore out half way through a dig: like a player, it makes a new one and carries on from where it got
     * to, rather than stopping the whole plan. Twice per step at most, then it says so honestly.
     */
    static boolean retool(PlayerAssistantEntity a, PaPlan.Step cur) {
        if (cur.auto || cur.retools >= 2 || !NO_PICK.matcher(cur.fail).find() || PaItems.hasPickaxe(a)) return false;
        PaPlan.Step pick = PaOrders.pickaxeStep(a);
        if (pick == null) return false;
        cur.retools++;
        rearm(a, cur);
        a.plan.steps.add(0, cur);
        a.plan.steps.add(0, pick);
        PaTalk.comment(a, 1, "My pickaxe wore out. Making a new one, then back to it.");
        return true;
    }

    private static final java.util.regex.Pattern NO_REACH = java.util.regex.Pattern.compile("(?i)(couldn'?t reach|can'?t reach|can'?t get to|no way to|got stuck)");

    /**
     * It couldn't reach something (the crafting table, a chest, the site): if it has dug itself into a pit it climbs
     * out first; either way it tries the step once more before it troubles its player.
     */
    static boolean unstick(PlayerAssistantEntity a, PaPlan.Step cur) {
        if (cur.auto || cur.retools >= 2 || !NO_REACH.matcher(cur.fail).find()) return false;
        cur.retools++;
        boolean pit = PaClimb.trapped(a);
        if (pit) a.mind.climbing = 30;
        rearm(a, cur);
        a.plan.steps.add(0, cur);
        PaTalk.comment(a, 1, pit ? "Oops, I've dug myself into a hole. Climbing out, then I'll try again." : "Couldn't get there. Let me try another way.");
        return true;
    }

    /**
     * Nothing of the kind within reach (no trees round here): like a player it looks further afield, says where it is
     * going, walks there and gathers round that spot. Once per step; within twice its roam, never past 96 blocks.
     */
    static boolean farther(PlayerAssistantEntity a, PaPlan.Step cur) {
        if (cur.auto || cur.retools >= 2 || cur.centre != null || cur.target <= 0) return false;
        com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind kind = null;
        for (Job j : cur.jobs) if (j.type() == Job.Type.GATHER) kind = j.kind();
        if (kind == null) return false;
        PaData.Rec r = a.rec();
        int roam = r == null ? 32 : r.roam;
        BlockPos found = PaFind.nearest(a, kind, Math.min(96, Math.max(48, roam * 2)));
        if (found == null) return false;
        cur.retools++;
        rearm(a, cur);
        cur.centre = found;
        BlockPos stand = beside(a, found);                                 // [pa33d] beside it, not inside the trunk
        cur.jobs.add(0, Job.goTo(stand.getX() + " " + stand.getY() + " " + stand.getZ()));
        a.plan.steps.add(0, cur);
        int far = (int) Math.sqrt(a.blockPosition().distSqr(found));
        PaTalk.reply(a, "There's none right here, but I can see " + kind.label + " about " + far + " blocks "
            + PaOrders.bearing(a.position(), Vec3.atCenterOf(found)) + ". Heading there.");
        return true;
    }

    /** [pa33d] Somewhere to stand beside a block (the side nearest it): open, with ground under it. */
    static BlockPos beside(PlayerAssistantEntity a, BlockPos b) {
        BlockPos best = b;
        double bd = Double.MAX_VALUE;
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy >= -3; dy--) {
                BlockPos q = b.relative(dir).above(dy);
                if (!a.level().getBlockState(q).getCollisionShape(a.level(), q).isEmpty()) break;
                if (a.level().getBlockState(q.below()).getCollisionShape(a.level(), q.below()).isEmpty()) continue;
                double d = q.distSqr(a.blockPosition());
                if (d < bd) { bd = d; best = q.immutable(); }
                break;
            }
        }
        return best;
    }

    /** Ready a step to run again, planning only what is still missing and keeping the progress counted so far. */
    static void rearm(PlayerAssistantEntity a, PaPlan.Step s) {
        int left = s.target > 0 && s.base >= 0 && !s.count.isEmpty()
            ? Math.max(1, s.target - Math.max(0, PaItems.count(a, s.count) - s.base)) : 0;
        if (left > 0 && !s.make.isEmpty()) {
            com.jrpetty.mcassistant.entity.CraftPlanner.Result cr = com.jrpetty.mcassistant.entity.CraftPlanner.plan(a, s.make, left);
            if (!cr.jobs().isEmpty()) { s.jobs.clear(); s.jobs.addAll(cr.jobs()); }
        } else if (left > 0) {
            java.util.List<Job> again = new java.util.ArrayList<>();
            for (Job j : s.jobs) again.add(j.type() == Job.Type.GATHER ? Job.gather(j.kind(), left + 3) : j);
            s.jobs.clear();
            s.jobs.addAll(again);
        }
        s.fail = "";
        s.fed = false;
        s.started = -1;
        s.keepBase = s.base >= 0;
    }

    /** The first sentence of the engine's reason ("I can't make torch, I'm missing 1 coal. It's not in..." -> the first part). */
    static String shortReason(String why) {
        String w = why.replaceFirst("(?i)^i can'?t make [^,—-]*[,—-]\\s*", "").trim();
        int dot = w.indexOf(". ");
        return dot > 0 ? w.substring(0, dot + 1) : w;
    }

    private static String lower(String s) {
        if (s.isEmpty() || s.matches("^I[ '’].*") || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String trimEnd(String s) {
        s = s.trim();
        return s.endsWith(".") || s.endsWith("!") || s.endsWith("?") ? s : s + ".";
    }

    // ------------------------------------------------------------------ what it is doing, for the plate and the HUD

    static void state(PlayerAssistantEntity a) {
        String icon, line;
        int pct = -1;
        PaPlan.Step cur = a.plan.current;
        if (a.getTarget() != null && a.getTarget().isAlive()) {
            icon = "fight";
            line = "Fighting a " + a.getTarget().getName().getString().toLowerCase();
        } else if (a.plan.paused) {
            icon = "paused";
            PaChallenge.Game game = a.getOwnerId() == null ? null : PaChallenge.GAMES.get(a.getOwnerId());
            line = a.isSleeping() ? "Asleep" : a.sleepWanted ? "Off to bed"
                : game != null ? (game.kind.equals("race") ? "Racing you" : game.kind.equals("mine") ? "Mining contest: " + game.mineScore + " to your " + game.ownerScore
                    : "Shooting contest: " + game.mineScore + " to your " + game.ownerScore)
                : "Paused (" + a.plan.total() + " waiting)";   // [pa33d]
        } else if (cur != null) {
            icon = cur.kind.toLowerCase();
            String tally = cur.tally(a);
            line = cap(hud(cur.label)) + (tally.isEmpty() ? "" : " " + tally);
            pct = cur.percent(a);
        } else if (a.mind.post != null) {
            icon = "guard";
            line = "Guarding";
        } else {
            icon = switch (a.getMode()) { case FOLLOW -> "follow"; case GUARD -> "guard"; case STAY -> "stay"; };
            line = switch (a.getMode()) { case FOLLOW -> "Following"; case GUARD -> "Guarding you"; case STAY -> "Waiting"; };
        }
        a.setState(icon + "|" + line + "|" + pct);
    }

    /** "gather 16 logs" -> "chopping logs"; the rest -> "-ing" ("build a house" -> "building a house"). */
    static String hud(String label) {
        if (label.matches("^gather \\d+ logs.*")) return "chopping logs";
        if (label.matches("^gather \\d+ stone.*")) return "mining stone";
        String g = ing(label);
        return g.replaceFirst(" \\d+ ", " ");
    }

    public static String ing(String label) {
        int sp = label.indexOf(' ');
        String verb = sp < 0 ? label : label.substring(0, sp);
        String rest = sp < 0 ? "" : label.substring(sp);
        String v = switch (verb) {
            case "gather" -> "gathering"; case "make" -> "making"; case "mine" -> "mining"; case "dig" -> "digging";
            case "build" -> "building"; case "smelt" -> "smelting"; case "put" -> "putting"; case "fetch" -> "fetching";
            case "bring" -> "bringing"; case "hunt" -> "hunting"; case "catch" -> "catching"; case "breed" -> "breeding";
            case "shear" -> "shearing"; case "light" -> "lighting"; case "sort" -> "sorting"; case "clear" -> "clearing";
            case "fuel" -> "fuelling"; case "head" -> "heading"; case "scout" -> "scouting"; case "tend" -> "tending";
            case "lay" -> "laying"; case "buy" -> "buying"; case "sell" -> "selling"; case "find" -> "finding";
            case "cook" -> "cooking"; case "get" -> "getting"; case "bridge" -> "bridging";
            default -> verb;
        };
        return v + rest;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ out of trouble

    private static void safety(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level() || owner.isSpectator()) return;
        boolean danger = a.isInLava() || (a.isUnderWater() && a.getAirSupply() < 80) || a.isInWall();
        Vec3 here = a.position();
        boolean moving = a.mind.lastPos == null || here.distanceToSqr(a.mind.lastPos) > 1.0;
        a.mind.lastPos = here;
        boolean wantsMove = a.getMode() == AssistantEntity.Mode.FOLLOW && a.plan.idle() && a.distanceToSqr(owner) > 20 * 20;
        boolean stuckWorking = false;
        if (a.plan.current != null) {
            int pct = a.plan.current.percent(a);
            if (pct == a.mind.lastPct && !moving) a.mind.sameFor++; else a.mind.sameFor = 0;
            a.mind.lastPct = pct;
            stuckWorking = a.mind.sameFor >= 60;                  // two minutes of nothing at all
        }
        if ((a.plan.idle() || a.plan.paused) && a.mind.climbing == 0 && PaClimb.trapped(a)) { a.mind.climbing = 30; return; }   // boxed in a pit: climb, don't teleport
        if (wantsMove && !moving) a.mind.stuckFor++; else if (moving) a.mind.stuckFor = 0;
        if (danger || a.mind.stuckFor >= 8 || stuckWorking) {
            a.mind.stuckFor = 0;
            a.mind.sameFor = 0;
            if (stuckWorking && a.plan.current != null && a.plan.current.fail.isEmpty()) {
                a.plan.current.fail = "I got stuck and couldn't get on with it.";
                a.clearQueue();
                a.task = null;
            }
            if (PaLife.bringTo(a, owner)) PaTalk.comment(a, 0, PaTalk.line(a, "stuck", "owner", owner.getGameProfile().getName()));
        }
    }

    /**
     * [pa33b] Boats. Its player gets into a boat: it hops into the free seat beside them (a boat seats two), so it goes
     * where they row. No seat (a chest boat, or someone else aboard): it keeps up along the shore, and its follow
     * catches up if it falls far behind. Its player steps out: so does it.
     */
    static void boat(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        net.minecraft.world.entity.Entity ride = owner.getVehicle();
        if (ride instanceof net.minecraft.world.entity.vehicle.Boat b) {
            if (a.getVehicle() == b) return;
            if (a.getMode() != AssistantEntity.Mode.FOLLOW || !a.plan.idle() || a.getTarget() != null) return;
            if (a.distanceToSqr(b) < 8 * 8 && b.getPassengers().size() < 2) {
                if (a.isPassenger()) a.stopRiding();
                a.getNavigation().stop();
                if (a.startRiding(b)) PaTalk.comment(a, 2, "Room for two!");
            } else if (a.getNavigation().isDone()) {
                a.getNavigation().moveTo(b, 1.2D);          // to the water's edge nearest them
            }
        } else if (a.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
            a.stopRiding();                                   // they got out: so does it
        }
    }

    /**
     * [pa33b] Remarks on what's going on, at its place in the triangle: after its player respawns, an order asked twice,
     * rain, a creeper by its player (after the warning), a village, a dirt hoard, a question left hanging, a long idle,
     * and in Joke Machine mode a running commentary every 10 to 20 seconds. All held to chattiness and rate limits.
     */
    static void banter(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        Mind m = a.mind;
        if (m.respawnQuipAt > 0 && a.tickCount >= m.respawnQuipAt) { m.respawnQuipAt = 0; PaBanter.respawned(a); return; }
        if (m.askedTwice) { m.askedTwice = false; PaBanter.quip(a, "quip.asked_twice", 1, null); return; }
        boolean raining = a.level().isRaining() && a.level().canSeeSky(owner.blockPosition());
        if (raining && !m.wasRaining) { m.wasRaining = true; PaBanter.quip(a, "quip.rain", 1, null); return; }
        if (!a.level().isRaining()) m.wasRaining = false;
        if (a.getTarget() == null) {
            for (net.minecraft.world.entity.monster.Creeper c : a.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, owner.getBoundingBox().inflate(10))) {
                if (c.isAlive() && a.tickCount - m.threatTick > 600) {
                    m.threatTick = a.tickCount;
                    PaTalk.reply(a, "Creeper near you, " + owner.getGameProfile().getName() + ". Back away from it.");   // the warning, plainly
                    PaBanter.after(a, "quip.creeper", "creeper");                                                        // then, maybe, a remark
                    return;
                }
            }
        }
        var town = com.jrpetty.mcassistant.entity.TownBlocks.townAt((net.minecraft.server.level.ServerLevel) a.level(), owner.blockPosition());
        java.util.UUID tid = town == null ? null : town.id();
        if (tid != null && !tid.equals(m.lastTown)) { m.lastTown = tid; PaBanter.quip(a, "quip.village", 1, null); return; }
        if (tid == null) m.lastTown = null;
        if (!m.sawDirt && owner.getInventory().countItem(net.minecraft.world.item.Items.DIRT) >= 192) { m.sawDirt = true; PaBanter.quip(a, "quip.dirt", 1, null); return; }
        PaOrders.Pending pend = PaOrders.pending(owner.getUUID());
        if (pend != null && pend.at() > 0 && a.level().getGameTime() - pend.at() > 45 * 20 && m.slowQuipped != pend.hashCode()) {
            m.slowQuipped = pend.hashCode();
            PaBanter.quip(a, "quip.slow_answer", 1, null);
            return;
        }
        if (a.distanceToSqr(owner) > 48 * 48 && a.getMode() == AssistantEntity.Mode.STAY && a.tickCount - m.lastComment > 12000) {
            PaBanter.quip(a, "quip.wandered", 2, null);
            return;
        }
        // its player standing still a while, for the commentary
        net.minecraft.world.phys.Vec3 op = owner.position();
        m.ownerStillFor = m.ownerLast != null && op.distanceToSqr(m.ownerLast) < 0.01 ? m.ownerStillFor + 20 : 0;
        m.ownerLast = op;
        if (PaBanter.jokeMachine(a)) {
            if (a.tickCount < m.jmNext) return;
            m.jmNext = a.tickCount + 200 + a.getRandom().nextInt(200);       // 10 to 20 seconds
            String what = m.ownerStillFor > 300 ? "jm.idle" : !a.plan.idle() ? "jm.progress" : a.getRandom().nextBoolean() ? "jm.weather" : "jm.idle";
            if (raining && what.equals("jm.weather")) what = "quip.rain";
            PaBanter.quip(a, what, 4, null);
            return;
        }
        if (a.plan.idle() && a.tickCount - m.lastSaid > 6000 && a.tickCount - m.lastComment > 6000) PaBanter.quip(a, "quip.idle", 2, null);
    }

    /** [pa33c-2b] Like a player: the last bed it slept in is where it comes back after a fall. */
    static void sleptIn(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || !a.isSleeping()) return;
        a.getSleepingPos().ifPresent(p -> {
            BlockPos head = PaOrders.bedHead(a.level(), p);
            if (head == null || head.equals(r.lastBed)) return;
            r.lastBed = head;
            r.lastBedDim = a.level().dimension().location().toString();
            PaData.touch();
        });
    }

    /** [pa33b] Joke Machine: a remark on something its player just did (mined, placed, jumped, ate, opened a chest). */
    public static void ownerDid(PlayerAssistantEntity a, String what) {
        if (!PaBanter.jokeMachine(a) || a.tickCount < a.mind.jmNext) return;
        a.mind.jmNext = a.tickCount + 200 + a.getRandom().nextInt(200);
        PaBanter.quip(a, what, 4, null);
    }

    /** Idle, it never stands in its player's face: it steps back to a couple of blocks off, as a friend would. */
    private static void personalSpace(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        if (!a.plan.idle() || a.getTarget() != null || a.mind.climbing > 0 || a.isPassenger()) return;
        double d2 = a.distanceToSqr(owner);
        if (d2 > 1.8 * 1.8) return;
        Vec3 away = a.position().subtract(owner.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) away = Vec3.directionFromRotation(0, owner.getYRot()).multiply(1, 0, 1);
        Vec3 to = owner.position().add(away.normalize().scale(2.8));
        a.getNavigation().moveTo(to.x, to.y, to.z, 0.8D);
    }

    private static void post(PlayerAssistantEntity a) {
        BlockPos p = a.mind.post;
        if (p == null || a.getTarget() != null || !a.plan.idle()) return;
        if (a.blockPosition().distSqr(p) > 6 * 6 && a.getNavigation().isDone()) a.walkTo(p, 1.0D);
    }

    /** May it work this block on its own, by how far its player lets it roam (from where the job began)? */
    public static boolean withinRoam(PlayerAssistantEntity a, BlockPos pos) {
        PaData.Rec r = a.rec();
        int roam = r == null ? 32 : Math.max(16, Math.min(PaConfig.roamMax(), r.roam));
        BlockPos c = a.mind.roamCentre != null ? a.mind.roamCentre : a.blockPosition();
        long dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
        return dx * dx + dz * dz <= (long) roam * roam;
    }

    // ------------------------------------------------------------------ a companion's remarks

    private static void companion(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        String on = owner.getGameProfile().getName();
        var pers = a.personality();
        PaPhrases.Dice rnd = a.getRandom()::nextInt;
        long day = a.level().getDayTime() / 24000L;
        long time = a.level().getDayTime() % 24000L;
        boolean near = a.distanceToSqr(owner) < 48 * 48;
        if (near && time >= 12300 && time < 13000 && a.mind.nightWarnedDay != day && a.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            a.mind.nightWarnedDay = day;
            PaTalk.comment(a, 1, PaTalk.line(a, "night", "owner", on));
        }
        if (near && owner.getHealth() <= 6 && owner.getHealth() > 0 && a.tickCount - a.mind.ownerHurtTick > 900) {
            a.mind.ownerHurtTick = a.tickCount;
            PaTalk.comment(a, 1, PaTalk.line(a, "owner_hurt", "owner", on));
            PaBanter.after(a, "quip.low_health", null);                  // after the warning, never instead
        }
        // [pa33b] hurt with nothing to eat: it says so and asks, with ways to fix it (it heals by eating, like a player)
        if ((a.getHealth() < a.getMaxHealth() * 0.5F || a.hunger.food() <= 6) && a.countFood() == 0 && a.getTarget() == null   // [pa33c-2b] or starving
                && a.tickCount - a.mind.askedFoodTick > 1200) {
            a.mind.askedFoodTick = a.tickCount;
            PaTalk.reply(a, PaTalk.line(a, "no_food", "owner", on),
                List.of(new Choice("Go hunting", "hunt for food"), new Choice("I'll give you some", "#pack")));
            return;
        }
        if (a.getHealth() < a.getMaxHealth() * 0.3F && a.tickCount - a.mind.meHurtTick > 900) {
            a.mind.meHurtTick = a.tickCount;
            PaTalk.comment(a, 1, PaTalk.line(a, "me_hurt", "owner", on));
        }
        int dia = PaItems.count(a, "diamond");
        if (a.mind.lastDiamonds >= 0 && dia > a.mind.lastDiamonds) PaTalk.comment(a, 0, PaTalk.line(a, "diamonds", "owner", on));
        a.mind.lastDiamonds = dia;
        if (near && a.plan.idle() && a.mind.tips < 3 && a.tickCount - a.mind.lastTip > 12000 && a.tickCount - a.mind.lastSaid > 2400) {
            a.mind.lastTip = a.tickCount;
            a.mind.tips++;                                      // a few tips a session, then it trusts you to know
            PaTalk.comment(a, 2, PaTalk.line(a, "tip", "owner", on));
        }
    }

    // ------------------------------------------------------------------ auto-help

    private static void autoHelp(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || !r.autoHelp || !a.plan.idle() || a.plan.paused || a.getTarget() != null || a.task != null || a.jobCount() > 0) return;
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level() || a.distanceToSqr(owner) > 64 * 64) return;
        if (a.getMode() == AssistantEntity.Mode.STAY || a.mind.post != null) return;
        if (a.tickCount - a.mind.lastAuto < PaGrowth.autoHelpSeconds(a.veteranLevel()) * 20) return;   // [pa35] more often with its level
        // 1. danger near its player: it steps in (noticed further off with its level)
        for (Monster m : a.level().getEntitiesOfClass(Monster.class, owner.getBoundingBox().inflate(PaGrowth.perception(a.veteranLevel())))) {
            if (!m.isAlive() || m instanceof net.minecraft.world.entity.monster.Creeper && !a.canSnipeCreepers()) continue;
            if (veto(a, "fight")) break;
            a.mind.lastAuto = a.tickCount;
            a.equipBestWeapon();
            a.setTarget(m);
            if (a.tickCount - a.mind.threatTick > 600) {
                a.mind.threatTick = a.tickCount;
                offer(a, "fight", "deal with that " + m.getName().getString().toLowerCase());
            }
            return;
        }
        BlockPos base = PaOrders.homeOf(a) != null ? PaOrders.homeOf(a) : owner.blockPosition();
        // 2. a full pack: put it away
        long used = a.getInventoryItems().stream().filter(s -> !s.isEmpty()).count();
        if (used >= 22 && !veto(a, "stash") && a.findChestWith(s -> true, 16) != null) {
            autoStep(a, "stash", "put my things away", PaIntent.Kind.DEPOSIT, Job.deposit(), null);
            return;
        }
        // 3. a furnace gone cold with work in it
        if (!veto(a, "fuel") && a.countMatching(s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL)
                || s.is(net.minecraft.tags.ItemTags.PLANKS)) > 0) {
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-10, -3, -10), base.offset(10, 3, 10))) {
                if (a.level().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f && !f.getItem(0).isEmpty() && f.getItem(1).isEmpty()
                        && PaChests.allowed(a, a.level(), p)) {
                    autoStep(a, "fuel", "keep the furnace fuelled", PaIntent.Kind.FUEL, null, new PaTask(PaTask.Kind.FUEL, p.immutable(), "", 0));
                    return;
                }
            }
        }
        // 4. ripe crops near the base
        if (!veto(a, "farm")) {
            int ripe = 0;
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-12, -2, -12), base.offset(12, 2, 12))) {
                if (PaTaskGoal.ripe(a.level().getBlockState(p)) && !PaSafety.offLimits(a, p) && ++ripe >= 4) break;
            }
            if (ripe >= 4) {
                autoStep(a, "farm", "harvest the ripe crops", PaIntent.Kind.FARM, Job.farm(), null);
                return;
            }
        }
        // 5. its player short of food, and it has some
        int ownerFood = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            var s = owner.getInventory().getItem(i);
            if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null) ownerFood += s.getCount();
        }
        if (ownerFood == 0 && owner.getFoodData().getFoodLevel() < 14 && a.countFood() >= 4 && !veto(a, "food")) {
            autoStep(a, "food", "bring you some food", PaIntent.Kind.BRING_FOOD, Job.give(PaMemoryAct.foodWord(a), 4), null);   // [pa35] the favourite
            return;
        }
        // 6. dark ground round its player at night, and torches to hand
        if (!veto(a, "light") && a.countMatching(s -> s.is(net.minecraft.world.item.Items.TORCH)) >= 4
                && a.level().getBrightness(LightLayer.BLOCK, owner.blockPosition()) < 5 && !a.level().isDay() && a.level().canSeeSky(owner.blockPosition())
                && !PaSafety.offLimits(a, owner.blockPosition())) {
            autoStep(a, "light", "light up the dark ground round here", PaIntent.Kind.LIGHT_AREA, Job.torchArea(10), null);
            return;
        }
        // 7. now and then, the chests at home sorted
        if (!veto(a, "sort") && a.tickCount - a.mind.sortedAt > 20 * 60 * 20 && PaOrders.homeOf(a) != null) {
            int chests = 0;
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-8, -2, -8), base.offset(8, 2, 8))) {
                if (a.level().getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity && PaChests.allowed(a, a.level(), p)) chests++;
            }
            if (chests >= 2) {
                a.mind.sortedAt = a.tickCount;
                autoStep(a, "sort", "sort the chests", PaIntent.Kind.SORT, Job.sort(), null);
            }
        }
    }

    private static boolean veto(PlayerAssistantEntity a, String key) {
        return a.mind.vetoed.contains(key);
    }

    private static void autoStep(PlayerAssistantEntity a, String key, String what, PaIntent.Kind kind, @Nullable Job job, @Nullable PaTask task) {
        a.mind.lastAuto = a.tickCount;
        PaPlan.Step s = PaOrders.step(what, kind, 40);
        s.auto = true;
        if (job != null) s.jobs.add(job);
        s.task = task;
        a.plan.steps.add(s);
        offer(a, key, what);
    }

    /** Say what it is about to do by itself, with a veto. */
    private static void offer(PlayerAssistantEntity a, String key, String what) {
        PaData.Rec r = a.rec();
        if (r != null && r.chatty <= 0) return;
        if (a.tickCount - a.mind.lastSaid < 200) return;
        PaTalk.reply(a, PaTalk.line(a, key.equals("fight") ? "threat" : "autohelp", "what", what),
            List.of(new Choice("Thanks", "thanks"), new Choice("Don't do that", "#veto " + key)));
    }
}
