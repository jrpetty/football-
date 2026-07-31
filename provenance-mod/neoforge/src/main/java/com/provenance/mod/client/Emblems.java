package com.provenance.mod.client;

import com.provenance.core.MilestoneTier;
import net.minecraft.ChatFormatting;

/**
 * Milestone emblems, drawn from glyphs and colour rather than textures.
 *
 * <p>The specification asks for wooden through relic emblems with the top two
 * animated. This renders them without any art assets: a distinct glyph per tier
 * in a material-appropriate colour, with the Legendary and Server Relic tiers
 * cycling. Swapping in real textures later means changing this class only —
 * nothing else refers to emblem appearance.
 *
 * <p>Restraint is deliberate. An emblem sits beside an item name in a tooltip
 * and must not make an inventory harder to read, so it is one character wide
 * and never replaces the item's model.
 */
public final class Emblems {

    /** Cycle period for animated tiers, in milliseconds. */
    private static final long CYCLE_MILLIS = 1_600L;

    /** Server Relic cycles through these; Legendary through the first three. */
    private static final ChatFormatting[] RELIC_CYCLE = {
            ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.WHITE, ChatFormatting.AQUA
    };
    private static final ChatFormatting[] LEGENDARY_CYCLE = {
            ChatFormatting.LIGHT_PURPLE, ChatFormatting.DARK_PURPLE, ChatFormatting.WHITE
    };

    /** Set from the server's snapshot; when true, animated tiers hold one colour. */
    private static volatile boolean reducedAnimations;

    private Emblems() {
    }

    public static void setReducedAnimations(boolean value) {
        reducedAnimations = value;
    }

    public static boolean reducedAnimations() {
        return reducedAnimations;
    }

    /** One character, chosen to read clearly at tooltip size. */
    public static String glyph(MilestoneTier tier) {
        return switch (tier) {
            case INITIATED -> "▪";
            case PROVEN -> "◆";
            case SEASONED -> "◈";
            case VETERAN -> "✦";
            case MASTER -> "✧";
            case LEGENDARY -> "✵";
            case SERVER_RELIC -> "✹";
        };
    }

    /**
     * The tier's colour. Animated tiers advance on a timer unless reduced
     * animations are on, in which case they hold their first colour.
     */
    public static ChatFormatting style(MilestoneTier tier) {
        return switch (tier) {
            case INITIATED -> ChatFormatting.DARK_GRAY;     // wooden
            case PROVEN -> ChatFormatting.GRAY;             // iron
            case SEASONED -> ChatFormatting.GOLD;           // copper
            case VETERAN -> ChatFormatting.YELLOW;          // gold
            case MASTER -> ChatFormatting.AQUA;             // diamond
            case LEGENDARY -> cycle(LEGENDARY_CYCLE);
            case SERVER_RELIC -> cycle(RELIC_CYCLE);
        };
    }

    private static ChatFormatting cycle(ChatFormatting[] colours) {
        if (reducedAnimations) {
            return colours[0];
        }
        int step = (int) ((System.currentTimeMillis() / (CYCLE_MILLIS / colours.length)) % colours.length);
        return colours[step];
    }

    /** ARGB border colour for the history screen, matching the tier. */
    public static int borderColour(int tierIndex) {
        if (tierIndex < 0) {
            return 0xFF404040;
        }
        MilestoneTier tier = MilestoneTier.byIndex(tierIndex);
        return switch (tier) {
            case INITIATED -> 0xFF8B5A2B;
            case PROVEN -> 0xFFC0C0C0;
            case SEASONED -> 0xFFB87333;
            case VETERAN -> 0xFFFFD700;
            case MASTER -> 0xFF4EE2EC;
            case LEGENDARY -> animatedArgb(0xFFDA70D6, 0xFFFFFFFF);
            case SERVER_RELIC -> animatedArgb(0xFFFF4500, 0xFFFFD700);
        };
    }

    /** Eases between two colours so a relic's border breathes rather than flashes. */
    private static int animatedArgb(int from, int to) {
        if (reducedAnimations) {
            return from;
        }
        double phase = (System.currentTimeMillis() % CYCLE_MILLIS) / (double) CYCLE_MILLIS;
        double eased = (1.0d - Math.cos(phase * Math.PI * 2.0d)) / 2.0d;
        int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, eased);
        int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, eased);
        int b = lerp(from & 0xFF, to & 0xFF, eased);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }
}
