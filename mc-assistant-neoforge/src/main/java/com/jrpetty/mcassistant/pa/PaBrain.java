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
        @Nullable public BlockPos post;
        @Nullable public BlockPos roamCentre;
        long nightWarnedDay = -1;
        int ownerHurtTick = -100000, meHurtTick = -100000, lastTip, lastAuto, stuckFor, sortedAt = -100000;
        int lastDiamonds = -1, threatTick = -100000;
        @Nullable Vec3 lastPos;
        public final Set<String> vetoed = new HashSet<>();
        int lastPct = -1, sameFor;
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
        if (t % 5 == 0) feed(a);                                  // never skipped: stop and orders must be prompt
        if (!spare) return;
        if (t % 10 == 0) state(a);
        if (t % 40 == 0) {
            safety(a);
            companion(a);
            post(a);
        }
        if (t % 100 == 0) autoHelp(a);
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
            a.mind.roamCentre = a.blockPosition();
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
        if (cur.stopAtTarget && cur.target > 0 && pct >= 100 && running) {
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
        if (!cur.fail.isEmpty() && retool(a, cur)) return;
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
            PaTalk.reply(a, PaPhrases.line("cant", a.personality(), a.getRandom()::nextInt, "what", trimEnd(cur.fail)), fixes);
            return;
        }
        if (r != null && !cur.auto) { r.jobsDone++; PaData.touch(); }
        String res = cur.task != null && !cur.task.result.isEmpty() ? cur.task.result : "";
        boolean last = plan.steps.stream().noneMatch(s -> !s.auto);
        if (cur.auto) {
            if (!res.isEmpty()) PaTalk.comment(a, 2, res);
            return;
        }
        String line = PaPhrases.line(last ? "done" : "step", a.personality(), a.getRandom()::nextInt, "did", PaPhrases.did(cur.label), "job", cur.label);
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
            line = "Paused (" + a.plan.total() + " waiting)";
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
        if (wantsMove && !moving) a.mind.stuckFor++; else if (moving) a.mind.stuckFor = 0;
        if (danger || a.mind.stuckFor >= 8 || stuckWorking) {
            a.mind.stuckFor = 0;
            a.mind.sameFor = 0;
            if (stuckWorking && a.plan.current != null && a.plan.current.fail.isEmpty()) {
                a.plan.current.fail = "I got stuck and couldn't get on with it.";
                a.clearQueue();
                a.task = null;
            }
            if (PaLife.bringTo(a, owner)) PaTalk.comment(a, 0, PaPhrases.line("stuck", a.personality(), a.getRandom()::nextInt, "owner", owner.getGameProfile().getName()));
        }
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
            PaTalk.comment(a, 1, PaPhrases.line("night", pers, rnd, "owner", on));
        }
        if (near && owner.getHealth() <= 6 && owner.getHealth() > 0 && a.tickCount - a.mind.ownerHurtTick > 900) {
            a.mind.ownerHurtTick = a.tickCount;
            PaTalk.comment(a, 1, PaPhrases.line("owner_hurt", pers, rnd, "owner", on));
        }
        if (a.getHealth() < a.getMaxHealth() * 0.3F && a.tickCount - a.mind.meHurtTick > 900) {
            a.mind.meHurtTick = a.tickCount;
            PaTalk.comment(a, 1, PaPhrases.line("me_hurt", pers, rnd, "owner", on));
        }
        int dia = PaItems.count(a, "diamond");
        if (a.mind.lastDiamonds >= 0 && dia > a.mind.lastDiamonds) PaTalk.comment(a, 0, PaPhrases.line("diamonds", pers, rnd, "owner", on));
        a.mind.lastDiamonds = dia;
        if (near && a.plan.idle() && a.tickCount - a.mind.lastTip > 12000 && a.tickCount - a.mind.lastSaid > 2400) {
            a.mind.lastTip = a.tickCount;
            PaTalk.comment(a, 2, PaPhrases.line("tip", pers, rnd, "owner", on));
        }
    }

    // ------------------------------------------------------------------ auto-help

    private static void autoHelp(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || !r.autoHelp || !a.plan.idle() || a.plan.paused || a.getTarget() != null || a.task != null || a.jobCount() > 0) return;
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level() || a.distanceToSqr(owner) > 64 * 64) return;
        if (a.getMode() == AssistantEntity.Mode.STAY || a.mind.post != null) return;
        if (a.tickCount - a.mind.lastAuto < 300) return;
        // 1. danger near its player: it steps in
        for (Monster m : a.level().getEntitiesOfClass(Monster.class, owner.getBoundingBox().inflate(12))) {
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
            autoStep(a, "food", "bring you some food", PaIntent.Kind.BRING_FOOD, Job.give("food", 4), null);
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
        PaTalk.reply(a, PaPhrases.line("autohelp", a.personality(), a.getRandom()::nextInt, "what", what),
            List.of(new Choice("Thanks", "thanks"), new Choice("Don't do that", "#veto " + key)));
    }
}
