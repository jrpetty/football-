package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;

import javax.annotation.Nullable;

/**
 * One queued piece of work. The assistant runs jobs in the order they were
 * given: finish one, start the next. Standing orders (follow/guard/stay) are
 * NOT jobs — they're what it does when the queue is empty.
 */
public record Job(Type type, @Nullable GatherGoal.Kind kind, int amount) {

    public enum Type { GATHER, DEPOSIT }

    public static Job gather(GatherGoal.Kind kind, int amount) {
        return new Job(Type.GATHER, kind, Math.max(1, Math.min(64, amount)));
    }

    public static Job deposit() {
        return new Job(Type.DEPOSIT, null, 0);
    }

    public String label() {
        return type == Type.GATHER
            ? "gather " + amount + " " + (kind != null ? kind.label : "?")
            : "deposit loot";
    }
}
