package com.jrpetty.mcassistant.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * [vision27] A folk's name tag cut down to its name. The server writes the whole tag (AssistantEntity: the trade's
 * glyph, ⚒ or ⚠ for a folk that is stuck, ✦ and its level, its name or a ruler's title, its badges, its health as
 * 20/20❤), which is a lot to read over every head in a crowd, and on film. "Name only" keeps the name (with a
 * ruler's title) and drops the rest. Only the chat classes: no drawing here (the tests use it on the server).
 */
public final class PlateText {

    private PlateText() {}

    /**
     * The name, and what is worth knowing at a glance: the ⚠ of a folk that is stuck and its health when it is hurt
     * (both in their own colours); the glyph of one at work, its level and its badges left off.
     */
    public static Component tidy(Component tag) {
        if (tag.getSiblings().isEmpty()) return nameOnly(tag);
        MutableComponent out = Component.empty();
        boolean stuck = false;
        Component hearts = null;
        for (Component part : tag.getSiblings()) {
            String s = part.getString().trim();
            if (s.equals("\u26A0")) stuck = true;
            if (s.endsWith("\u2764")) {
                int slash = s.indexOf('/');
                try {
                    if (slash > 0 && Integer.parseInt(s.substring(0, slash)) < Integer.parseInt(s.substring(slash + 1, s.length() - 1))) hearts = part;
                } catch (NumberFormatException e) {
                    hearts = part;
                }
            }
        }
        if (stuck) out.append(Component.literal("\u26A0 ").withStyle(net.minecraft.ChatFormatting.RED));
        out.append(nameOnly(tag));
        if (hearts != null) out.append(Component.literal(" ")).append(hearts.copy());
        return out;
    }

    /** The name alone out of a whole tag; a tag of one piece (a folk shown off, a plain name) as it is. */
    public static Component nameOnly(Component tag) {
        if (tag.getSiblings().isEmpty()) return strip(tag.getString()).equals(tag.getString()) ? tag : Component.literal(strip(tag.getString()));
        for (Component part : tag.getSiblings()) {
            String s = part.getString().trim();
            if (s.isEmpty() || isGlyph(s) || s.startsWith("✦") || s.endsWith("❤")) continue;
            MutableComponent name = part.copy();
            return name;
        }
        return Component.literal(strip(tag.getString()));
    }

    private static boolean isGlyph(String s) {
        return s.equals("⚒") || s.equals("⚠");
    }

    /** The same out of the bare words: no glyph at the front, no level, no health at the end. */
    static String strip(String s) {
        String t = s.trim();
        if (t.startsWith("⚒ ") || t.startsWith("⚠ ")) t = t.substring(2).trim();
        if (t.startsWith("✦")) {
            int sp = t.indexOf(' ');
            t = sp < 0 ? "" : t.substring(sp + 1).trim();
        }
        if (t.endsWith("❤")) {
            int sp = t.lastIndexOf(' ');
            t = sp < 0 ? "" : t.substring(0, sp).trim();
        }
        return t;
    }
}
