package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;

import javax.annotation.Nullable;

/**
 * One queued piece of work. The assistant runs jobs in the order they were
 * given: finish one, start the next. Standing orders (follow/guard/stay) are
 * usually NOT jobs — they're what it does when the queue is empty — but a
 * mode switch that arrives as part of a longer sentence ("gather logs then
 * follow me") queues as a MODE job so it happens in sequence.
 *
 * `arg` is the type-specific payload: recipe key for CRAFT, item word for
 * WITHDRAW, structure name for BUILD.
 */
public record Job(Type type, @Nullable GatherGoal.Kind kind, int amount,
                  @Nullable AssistantEntity.Mode mode, @Nullable String arg) {

    public enum Type { GATHER, DEPOSIT, MODE, GO_HOME, CRAFT, WITHDRAW, FARM, BUILD }

    /** Biggest single gather order (well past a full backpack of one item). */
    public static final int MAX_AMOUNT = 1024;

    public static Job gather(GatherGoal.Kind kind, int amount) {
        return new Job(Type.GATHER, kind, Math.max(1, Math.min(MAX_AMOUNT, amount)), null, null);
    }

    public static Job deposit() {
        return new Job(Type.DEPOSIT, null, 0, null, null);
    }

    public static Job mode(AssistantEntity.Mode mode) {
        return new Job(Type.MODE, null, 0, mode, null);
    }

    public static Job goHome() {
        return new Job(Type.GO_HOME, null, 0, null, null);
    }

    public static Job craft(String recipeKey, int amount) {
        return new Job(Type.CRAFT, null, Math.max(1, Math.min(64, amount)), null, recipeKey);
    }

    public static Job withdraw(String itemWord, int amount) {
        return new Job(Type.WITHDRAW, null, Math.max(1, Math.min(MAX_AMOUNT, amount)), null, itemWord);
    }

    public static Job farm() {
        return new Job(Type.FARM, null, 0, null, null);
    }

    public static Job build(String structure) {
        return new Job(Type.BUILD, null, 0, null, structure);
    }

    public String label() {
        return switch (type) {
            case GATHER -> "gather " + amount + " " + (kind != null ? kind.label : "?");
            case DEPOSIT -> "deposit loot";
            case MODE -> "switch to " + (mode != null ? mode.name().toLowerCase() : "?");
            case GO_HOME -> "go home";
            case CRAFT -> "craft " + amount + " " + arg;
            case WITHDRAW -> "withdraw " + amount + " " + arg;
            case FARM -> "tend the farm";
            case BUILD -> "build a " + arg;
        };
    }
}
