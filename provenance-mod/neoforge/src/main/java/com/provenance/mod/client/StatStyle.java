package com.provenance.mod.client;

/**
 * Gives each statistic a visual family, so a screenful of numbers reads as
 * groups rather than one undifferentiated column.
 *
 * <p>Colour does the work, carried by a drawn accent bar rather than a glyph.
 * Minecraft's default font falls back to the uniform font for most symbols, so
 * icon characters render inconsistently across resource packs; a filled
 * rectangle looks the same everywhere.
 *
 * <p>Colours are chosen to stay distinguishable for the most common form of
 * colour blindness by also differing in lightness, and every row still carries
 * its full text label — colour is a second channel here, never the only one.
 */
public final class StatStyle {

    /** A statistic's family: what kind of work it measures. */
    public enum Family {
        COMBAT("Combat", 0xFFE0603A),
        MINING("Mining", 0xFF4EC9E0),
        WOODCUTTING("Woodcutting", 0xFF8FBF5F),
        DIGGING("Digging", 0xFFC9A76B),
        FARMING("Farming", 0xFF7FD07F),
        FISHING("Fishing", 0xFF6FA8DC),
        UTILITY("Utility", 0xFF5FC9A8),
        DEFENCE("Defence", 0xFFB48EE0),
        MAINTENANCE("Upkeep", 0xFFE0A03A),
        TRAVEL("Travel", 0xFF9AA7B5);

        private final String label;
        private final int colour;

        Family(String label, int colour) {
            this.label = label;
            this.colour = colour;
        }

        public String label() {
            return label;
        }

        /** Full-strength ARGB, for the accent bar. */
        public int colour() {
            return colour;
        }

        /** Dimmed, for the row background behind the accent. */
        public int wash() {
            return (colour & 0x00FFFFFF) | 0x18000000;
        }
    }

    private StatStyle() {
    }

    public static Family familyOf(String statId) {
        return switch (statId) {
            case "kills", "damage_dealt", "hits_landed", "projectile_hits", "longest_kill_cm" -> Family.COMBAT;
            case "blocks_mined", "ores_mined", "deepest_block_y" -> Family.MINING;
            case "logs_chopped", "blocks_chopped" -> Family.WOODCUTTING;
            case "blocks_dug" -> Family.DIGGING;
            case "blocks_worked", "crops_harvested" -> Family.FARMING;
            case "catches_total", "fish_caught", "treasure_caught", "junk_caught" -> Family.FISHING;
            case "utility_uses", "blocks_cut" -> Family.UTILITY;
            case "damage_absorbed", "hits_received", "time_worn_ticks" -> Family.DEFENCE;
            case "repairs", "durability_restored" -> Family.MAINTENANCE;
            case "distance_cm" -> Family.TRAVEL;
            default -> Family.UTILITY;
        };
    }

    /**
     * True for statistics that headline their category — drawn larger so the
     * eye lands on the number that actually defines the item.
     */
    public static boolean isHeadline(String statId) {
        return switch (statId) {
            case "kills", "blocks_mined", "logs_chopped", "blocks_dug",
                 "blocks_worked", "catches_total", "damage_absorbed", "utility_uses" -> true;
            default -> false;
        };
    }
}
