package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerPlayer;

/**
 * [player15] Selling, letting and repurposing a building of yours: the buttons are on your shop's screen and the Your
 * life in town page; the property market (another round's) answers here. Until it lands, this says so plainly and points
 * to what the town does now (a house sold back to the town at the bank, a stall given up).
 */
public final class PropertyDesk {

    private PropertyDesk() {}

    /** The property market's screen for this (sell, let, repurpose), or a word on what can be done now. */
    public static String open(ServerPlayer p, String what, String from) {
        return switch (what) {
            case "sell" -> "!Selling a building on the town's property market isn't open yet. A house you own can be sold back to the town at the bank today.";
            case "let" -> "!Letting a building isn't open yet. A house you own can be let to a household now: stand in it and tell any folk \"let my house for 3\".";
            default -> "!Turning a building to another use isn't open yet: for now, change its kind on its Setup tab, and add what the new kind needs.";
        };
    }
}
