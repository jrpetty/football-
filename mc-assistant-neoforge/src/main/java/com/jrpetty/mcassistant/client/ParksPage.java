package com.jrpetty.mcassistant.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * [featbuild] The Parks page (WorksPages draws it): the town's park and statue gardens, each with its statue and plaque,
 * who is in it now and its ponds, with markers; how many gardens the town keeps for its size and wealth and why the next is
 * or is not on the builders' list; who is next to be honoured and their standing; and how many folk are the happier for a
 * statue near them.
 */
final class ParksPage {

    private ParksPage() {}

    static String build(WorksPages.Rows r, CompoundTag c) {
        if (c.isEmpty()) return "";
        ListTag greens = c.getList("greens", Tag.TAG_COMPOUND);
        int gardens = 0, parks = 0;
        for (Tag t : greens) if (((CompoundTag) t).getString("kind").equals("Park")) parks++; else gardens++;
        r.heading("Parks and gardens");
        if (greens.isEmpty()) r.text("None yet. A park comes at twenty folk (among the homes, with a fountain); statue gardens once the town builds"
            + " in stone and has somebody to honour.", UiStyle.TEXT_MUTED);
        for (Tag t : greens) {
            CompoundTag g = (CompoundTag) t;
            boolean garden = !g.getString("kind").equals("Park");
            String head = g.getString("kind") + ", " + g.getInt("dist") + " blocks from the square" + (g.getInt("there") > 0 ? " · " + g.getInt("there") + " there now" : "");
            r.showMe(head, UiStyle.TEXT, WorksPages.at(g), garden ? "the statue garden" : "the park",
                garden ? "its statue on the plinth in the middle, benches round it, the ponds at the back." : "its fountain in the middle and the benches round it.",
                "its ground is not loaded.");
            if (garden) {
                String statue = g.getString("statue");
                r.text(statue.isEmpty() ? "The plinth waits for its statue: the mason raises it out of the stores (an armour stand and a block of stone)."
                    : "Statue of " + statue + ". Plaque: " + g.getString("plaque") + ".", statue.isEmpty() ? UiStyle.WARNING : UiStyle.SUCCESS, 1);
                r.text("Ponds: " + g.getInt("water") + " of 4 filled (only where the water will stay; a broken rim empties its pond first).", UiStyle.TEXT_FAINT, 1);
            } else {
                r.text(g.getString("status"), UiStyle.TEXT_FAINT, 1);
            }
        }
        int folk = c.getInt("folk"), coins = c.getInt("coins"), keeps = c.getInt("gardensFor");
        r.heading("Statue gardens");
        r.text("The town keeps " + keeps + (keeps == 1 ? " statue garden" : " statue gardens") + " for " + folk + " folk and " + coins
            + " coins in the treasury (one at sixteen folk, one more for every sixteen, one more for three hundred coins; four at most). It has " + gardens + ".",
            UiStyle.TEXT);
        String status;
        if (!c.getBoolean("stone")) status = "Not yet: a statue needs a mason's stone, and the town is still in the Wood Age.";
        else if (c.getBoolean("onList")) status = "A statue garden is on the builders' list, on a lot facing the square (a civic spot, never a home's).";
        else if (c.getBoolean("wanted")) status = "A statue garden is wanted: it goes on the builders' list with the other amenities.";
        else if (gardens >= keeps) status = keeps == 0 ? "Not yet: the first comes at sixteen folk." : "As many as the town keeps for now; another as it grows or prospers.";
        else status = "Waiting for somebody notable enough to honour (a standing of " + c.getInt("notable") + ").";
        r.text(status, UiStyle.TEXT_MUTED);
        r.heading("Next to be honoured");
        ListTag next = c.getList("next", Tag.TAG_COMPOUND);
        if (next.isEmpty()) r.text("Nobody yet: the grown folk of the town are all honoured, or there are none.", UiStyle.TEXT_MUTED);
        int notable = c.getInt("notable");
        for (Tag t : next) {
            CompoundTag n = (CompoundTag) t;
            int score = n.getInt("score");
            WorksPages.Row row = r.bar(n.getString("name"), Math.min(1F, score / (float) Math.max(1, notable * 2)), score >= notable ? UiStyle.SUCCESS_FILL : UiStyle.INFO_FILL,
                score + (score >= notable ? " notable" : ""));
            row.tip = java.util.List.of(n.getString("name") + ", " + n.getString("trade"), "Known for " + n.getString("known"), "Standing " + score + " (notable at " + notable + ")");
            r.text(DefencesPage.cap(n.getString("trade")) + ", known for " + n.getString("known"), UiStyle.TEXT_FAINT, 1);
        }
        r.text("Standing comes from what the rest of the town thinks of a folk, its years at its trade, its deeds and its offices.", UiStyle.TEXT_FAINT);
        r.heading("The good of it");
        r.text(c.getInt("happier") + " folk are the happier for a statue near them (+3 within sixteen blocks, or living within twenty-four; +5 for one's own"
            + " or one's kin's). A home by a park or garden is the happier and lets for more. Folk visit in their free time: a sit on a bench, a stroll,"
            + " a chat.", UiStyle.TEXT);
        return "Parks: " + parks + (parks == 1 ? " park, " : " parks, ") + gardens + (gardens == 1 ? " statue garden" : " statue gardens");
    }
}
