package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [pa33] The Player AI Assistant's list of what it was asked, one step per thing ("gather 16 logs", "build a house").
 * A step is carried out by the original engine's jobs (fed into its queue when the step starts) or by one of its own
 * tasks (PaTask). Kept apart from the engine's queue so the player can see, reorder, pause and cancel what they
 * asked for, each with its progress and a guess at how long it will take; and so "undo" can put the list back.
 */
public final class PaPlan {

    public static final class Step {
        public String label = "";
        /** The kind of order (PaIntent.Kind name): for its icon and its progress. */
        public String kind = "";
        public final List<Job> jobs = new ArrayList<>();
        @Nullable public PaTask task;
        /** Its guess at the time it takes, in seconds. */
        public int eta;
        /** Progress counted from the pack: items of this kind ("logs", "stone", "iron", ...) up to target. */
        public String count = "";
        public int target, base = -1;
        /** Stop the step once the pack holds the target (a mine dug "until you have 20 iron"). */
        public boolean stopAtTarget;
        public long started = -1;
        /** What went wrong, said by the engine while this step ran; empty when nothing did. */
        public String fail = "";
        /** The order as said, to try again. */
        public String said = "";
        /** Something it decided to do by itself (auto-help): any order from its player comes first. */
        public boolean auto;
        public boolean fed;
        /** Times it has stopped to make a new tool for this step (a pickaxe worn out half way). */
        public int retools;
        /** Keep the progress counted so far when the step starts again after a new tool. */
        public boolean keepBase;
        /** What CraftPlanner makes for this step ("planks", "chest"), so a retry plans only what is still missing. */
        public String make = "";

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("L", label);
            t.putString("K", kind);
            ListTag js = new ListTag();
            for (Job j : jobs) js.add(job(j));
            t.put("J", js);
            if (task != null) t.put("T", task.save());
            t.putInt("Eta", eta);
            t.putString("C", count);
            t.putInt("Tg", target);
            t.putBoolean("Stop", stopAtTarget);
            t.putString("Said", said);
            t.putString("Mk", make);
            t.putBoolean("Auto", auto);
            return t;
        }

        static Step load(CompoundTag t) {
            Step s = new Step();
            s.label = t.getString("L");
            s.kind = t.getString("K");
            for (Tag e : t.getList("J", Tag.TAG_COMPOUND)) {
                Job j = job((CompoundTag) e);
                if (j != null) s.jobs.add(j);
            }
            if (t.contains("T")) s.task = PaTask.load(t.getCompound("T"));
            s.eta = t.getInt("Eta");
            s.count = t.getString("C");
            s.target = t.getInt("Tg");
            s.stopAtTarget = t.getBoolean("Stop");
            s.said = t.getString("Said");
            s.make = t.getString("Mk");
            s.auto = t.getBoolean("Auto");
            return s;
        }

        /** How far along, 0..100, or -1 when it cannot be told. */
        public int percent(AssistantEntity a) {
            if (target > 0 && !count.isEmpty() && base >= 0) {
                int have = Math.max(0, PaItems.count(a, count) - base);
                return Math.min(100, have * 100 / target);
            }
            if (task != null) return task.percent();
            return -1;
        }

        /** "12/16" when counted, else "". */
        public String tally(AssistantEntity a) {
            if (target > 0 && !count.isEmpty() && base >= 0) {
                return Math.min(target, Math.max(0, PaItems.count(a, count) - base)) + "/" + target;
            }
            return "";
        }
    }

    public final List<Step> steps = new ArrayList<>();
    @Nullable public Step current;
    public boolean paused;
    /** The step that last failed and paused the plan, for its "Try again" choice. Not saved. */
    @Nullable public Step failed;
    /** What the list was before the last order: "undo" puts it back. */
    @Nullable private List<Step> before;
    @Nullable private Step beforeCurrent;

    public boolean idle() {
        return current == null && steps.isEmpty();
    }

    public boolean onlyAuto() {
        if (current != null && !current.auto) return false;
        for (Step s : steps) if (!s.auto) return false;
        return true;
    }

    public void remember() {
        before = new ArrayList<>(steps);
        beforeCurrent = current;
    }

    /** Put the list back as it was before the last order; true when there was something to put back. */
    public boolean undo() {
        if (before == null) return false;
        steps.clear();
        steps.addAll(before);
        current = null;
        if (beforeCurrent != null) {
            beforeCurrent.fed = false;
            beforeCurrent.started = -1;
            steps.add(0, beforeCurrent);
        }
        before = null;
        beforeCurrent = null;
        return true;
    }

    public void clear() {
        steps.clear();
        current = null;
        paused = false;
    }

    public void dropAuto() {
        steps.removeIf(s -> s.auto);
    }

    public int total() {
        return steps.size() + (current != null ? 1 : 0);
    }

    /** Every step in order, the one under way first. */
    public List<Step> all() {
        List<Step> out = new ArrayList<>();
        if (current != null) out.add(current);
        out.addAll(steps);
        return out;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        if (current != null) l.add(current.save());
        for (Step s : steps) l.add(s.save());
        t.put("Steps", l);
        t.putBoolean("Paused", paused);
        return t;
    }

    public void load(CompoundTag t) {
        steps.clear();
        current = null;
        for (Tag e : t.getList("Steps", Tag.TAG_COMPOUND)) steps.add(Step.load((CompoundTag) e));
        paused = t.getBoolean("Paused");
    }

    static CompoundTag job(Job j) {
        CompoundTag t = new CompoundTag();
        t.putString("Type", j.type().name());
        if (j.kind() != null) t.putString("Kind", j.kind().name());
        t.putInt("N", j.amount());
        if (j.mode() != null) t.putString("Mode", j.mode().name());
        if (j.arg() != null) t.putString("Arg", j.arg());
        return t;
    }

    @Nullable
    static Job job(CompoundTag t) {
        try {
            Job.Type type = Job.Type.valueOf(t.getString("Type"));
            GatherGoal.Kind kind = t.contains("Kind") ? GatherGoal.Kind.valueOf(t.getString("Kind")) : null;
            AssistantEntity.Mode mode = t.contains("Mode") ? AssistantEntity.Mode.valueOf(t.getString("Mode")) : null;
            return new Job(type, kind, t.getInt("N"), mode, t.contains("Arg") ? t.getString("Arg") : null);
        } catch (Exception e) {
            return null;
        }
    }
}
