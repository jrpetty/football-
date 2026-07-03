package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;

import javax.annotation.Nullable;

/**
 * One queued piece of work. The assistant runs jobs in the order they were
 * given: finish one, start the next. Standing orders (follow/guard/stay) are
 * usually NOT jobs — they're what it does when the queue is empty — but a
 * mode switch that arrives as part of a longer sentence ("gather logs then
 * follow me") queues as a MODE job so it happens in sequence.
 */
public record Job(Type type, @Nullable GatherGoal.Kind kind, int amount, @Nullable AssistantEntity.Mode mode) {

    public enum Type { GATHER, DEPOSIT, MODE }

    /** Biggest single gather order (well past a full backpack of one item). */
    public static final int MAX_AMOUNT = 1024;

    public static Job gather(GatherGoal.Kind kind, int amount) {
        return new Job(Type.GATHER, kind, Math.max(1, Math.min(MAX_AMOUNT, amount)), null);
    }

    public static Job deposit() {
        return new Job(Type.DEPOSIT, null, 0, null);
    }

    public static Job mode(AssistantEntity.Mode mode) {
        return new Job(Type.MODE, null, 0, mode);
    }

    public String label() {
        return switch (type) {
            case GATHER -> "gather " + amount + " " + (kind != null ? kind.label : "?");
            case DEPOSIT -> "deposit loot";
            case MODE -> "switch to " + (mode != null ? mode.name().toLowerCase() : "?");
        };
    }
}
