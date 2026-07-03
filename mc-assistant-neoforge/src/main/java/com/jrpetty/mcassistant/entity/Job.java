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

    public enum Type { GATHER, DEPOSIT, MODE, GO_HOME, CRAFT, WITHDRAW, FARM, BUILD, SMELT,
                       MINE, HUNT, SHEAR, GIVE, GOTO,
                       PATROL, CLEAR, TORCH_AREA, BRIDGE, BREED, HERD, FISH, CLEANUP, RECOVER }

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

    public static Job smelt(String itemWord, int amount) {
        return new Job(Type.SMELT, null, Math.max(1, Math.min(256, amount)), null, itemWord);
    }

    /** amount carries the target Y level here (can be negative — deepslate). */
    public static Job mine(int targetY) {
        return new Job(Type.MINE, null, Math.max(-58, Math.min(100, targetY)), null, null);
    }

    public static Job hunt(@Nullable String animal, int amount) {
        return new Job(Type.HUNT, null, Math.max(1, Math.min(16, amount)), null, animal);
    }

    public static Job shear(int amount) {
        return new Job(Type.SHEAR, null, Math.max(1, Math.min(16, amount)), null, null);
    }

    public static Job give(String itemWord, int amount) {
        return new Job(Type.GIVE, null, Math.max(1, Math.min(MAX_AMOUNT, amount)), null, itemWord);
    }

    public static Job goTo(String place) {
        return new Job(Type.GOTO, null, 0, null, place);
    }

    /** arg = "placeA|placeB" — loops between them until stopped. */
    public static Job patrol(String placeA, String placeB) {
        return new Job(Type.PATROL, null, 0, null, placeA + "|" + placeB);
    }

    /** arg = "WxD", e.g. "10x10". Clears 3 blocks high at the bot's level. */
    public static Job clear(String size) {
        return new Job(Type.CLEAR, null, 0, null, size);
    }

    /** amount = radius to light up with torches. */
    public static Job torchArea(int radius) {
        return new Job(Type.TORCH_AREA, null, Math.max(4, Math.min(24, radius)), null, null);
    }

    public static Job bridge() {
        return new Job(Type.BRIDGE, null, 0, null, null);
    }

    /** amount = pairs to breed; arg = animal word or null for any. */
    public static Job breed(@Nullable String animal, int pairs) {
        return new Job(Type.BREED, null, Math.max(1, Math.min(8, pairs)), null, animal);
    }

    /** amount = animals to lead (max 2); arg = animal word. */
    public static Job herd(String animal, int count) {
        return new Job(Type.HERD, null, Math.max(1, Math.min(2, count)), null, animal);
    }

    public static Job fish(int amount) {
        return new Job(Type.FISH, null, Math.max(1, Math.min(32, amount)), null, null);
    }

    public static Job cleanup() {
        return new Job(Type.CLEANUP, null, 0, null, null);
    }

    /** arg = "x y z" of the owner's death spot. */
    public static Job recover(String pos) {
        return new Job(Type.RECOVER, null, 0, null, pos);
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
            case SMELT -> "smelt " + amount + " " + arg;
            case MINE -> "dig a mine to Y" + amount;
            case HUNT -> "hunt " + amount + " " + (arg != null ? arg : "animals");
            case SHEAR -> "shear the sheep";
            case GIVE -> "hand over " + amount + " " + arg;
            case GOTO -> "go to " + arg;
            case PATROL -> "patrol " + (arg != null ? arg.replace("|", " <-> ") : "?");
            case CLEAR -> "clear a " + arg + " area";
            case TORCH_AREA -> "light up the area";
            case BRIDGE -> "bridge the gap";
            case BREED -> "breed " + (arg != null ? arg : "animals");
            case HERD -> "bring " + amount + " " + arg + " home";
            case FISH -> "catch " + amount + " fish";
            case CLEANUP -> "pick up loose items";
            case RECOVER -> "recover the owner's drops";
        };
    }
}
