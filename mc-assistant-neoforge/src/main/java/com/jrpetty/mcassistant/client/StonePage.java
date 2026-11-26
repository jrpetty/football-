package com.jrpetty.mcassistant.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * [featbuild] The Stone page (WorksPages draws it): the quarry (where, how big and deep, how much cut, fenced and lit, its
 * quarrymen and what each is at) and the stone yard (its stock of rough stone, stone bricks and bricks against what the
 * stores hold), each with a marker; the last week's flow (cut, dressed, carted to the stores, drawn by the builders); and
 * the builds waiting on stone from the yard.
 */
final class StonePage {

    private StonePage() {}

    static String build(WorksPages.Rows r, CompoundTag all) {
        if (all.isEmpty()) return "";
        CompoundTag q = all.getCompound("quarry"), y = all.getCompound("yard");
        r.heading("The quarry");
        if (!q.getBoolean("has")) {
            int folk = q.getInt("folk");
            r.text(folk < 10 ? "No quarry yet: the town opens one at ten folk (it has " + folk + "), once its storehouse stands."
                : q.getBoolean("wanted") ? "The council is looking for a site out past the growth line (" + q.getInt("growth")
                    + " blocks from the heart): a rock outcrop if there is one, off the roads, the farmland and the mine's ground."
                : "No quarry yet: the town wants its storehouse first.", UiStyle.TEXT_MUTED);
            if (!q.getString("old").isEmpty()) r.text("Closed quarries (their terraces left as safe steps): " + q.getString("old"), UiStyle.TEXT_FAINT);
        } else {
            r.showMe("A " + q.getInt("size") + "-across pit " + q.getInt("dist") + " blocks out, " + (q.getBoolean("outcrop") ? "at a rock outcrop" : "dug down")
                + ", " + q.getInt("deep") + " terraces deep", UiStyle.TEXT, WorksPages.at(q), "the quarry",
                "its terraces step down a block at a time; the fence and lanterns go round its rim.", "its ground is not loaded.");
            r.text(WorksPages.num(q.getLong("cut")) + " blocks cut so far. " + (q.getBoolean("fenced") ? "Fenced and lit all round." : "Its fence and lanterns are going up.")
                + " It stays out past the town's growth line (" + q.getInt("growth") + " blocks) and opens wider as it is worked; if the town grows out to it,"
                + " it closes and a new one opens further out.", UiStyle.TEXT_MUTED);
            ListTag men = q.getList("men", Tag.TAG_COMPOUND);
            if (men.isEmpty()) r.text("No quarrymen yet: the miners come first, then a spare hand.", UiStyle.WARNING);
            for (Tag t : men) {
                CompoundTag m = (CompoundTag) t;
                r.text(m.getString("name") + " (" + m.getString("trade") + "): " + m.getString("doing"), UiStyle.TEXT, 1);
            }
        }
        r.heading("The stone yard");
        if (!y.getBoolean("has")) {
            r.text("The yard is laid out beside the quarry when it opens: piles of rough stone, stone bricks and bricks, the mason's furnace and stonecutter.",
                UiStyle.TEXT_MUTED);
        } else {
            int rough = y.getInt("rough"), dressed = y.getInt("dressed"), brick = y.getInt("brick");
            int most = Math.max(240, Math.max(rough, Math.max(dressed, brick)));
            r.showMe("Stock: " + WorksPages.num(rough + dressed + brick) + " in the yard", UiStyle.TEXT, WorksPages.at(y), "the stone yard",
                "its piles stand a block high for every sixteen in stock.", "its ground is not loaded.");
            r.bar("Rough stone", WorksPages.frac(rough, most), 0xFF8A8A8A, WorksPages.num(rough) + " (stores " + y.getInt("storesRough") + ")");
            r.bar("Stone bricks", WorksPages.frac(dressed, most), UiStyle.INFO_FILL, WorksPages.num(dressed) + " (stores " + y.getInt("storesDressed") + ")");
            r.bar("Bricks", WorksPages.frac(brick, most), 0xFFB5523B, WorksPages.num(brick) + " (stores " + y.getInt("storesBrick") + ")");
            r.text("City buildings draw their stone from the yard first, then the stores. A quarryman carts a load to the stores whenever they run under"
                + " 64 cobblestone, 64 stone bricks or 32 bricks. The mason dresses cobblestone into stone bricks (a coal for eight) and fires clay into"
                + " bricks while a quarryman is at the yard.", UiStyle.TEXT_FAINT);
        }
        r.heading("This week's flow");
        ListTag flow = y.getList("flow", Tag.TAG_COMPOUND);
        if (flow.isEmpty()) r.text("Nothing has moved yet.", UiStyle.TEXT_MUTED);
        for (int i = flow.size() - 1; i >= 0; i--) {
            CompoundTag f = flow.getCompound(i);
            r.text("Day " + f.getLong("day") + ": cut " + f.getLong("cut") + " · dressed " + f.getLong("dressed") + " · to the stores " + f.getLong("carted")
                + " · drawn by builders " + f.getLong("drawn"), UiStyle.TEXT, 1);
        }
        r.heading("Waiting on stone");
        ListTag shorts = y.getList("short", Tag.TAG_STRING);
        if (shorts.isEmpty()) r.text("Nobody: every build has the stone it asks for.", UiStyle.SUCCESS);
        for (Tag t : shorts) r.text(DefencesPage.cap(t.getAsString()) + ". Its builders lay what they can and wait for the next loads.", UiStyle.DANGER);
        int total = y.getInt("rough") + y.getInt("dressed") + y.getInt("brick");
        return "Stone: " + (q.getBoolean("has") ? "the quarry at work" : "no quarry yet") + (y.getBoolean("has") ? ", " + WorksPages.num(total) + " in the yard" : "");
    }
}
