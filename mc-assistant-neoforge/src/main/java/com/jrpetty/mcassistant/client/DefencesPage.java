package com.jrpetty.mcassistant.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * [featbuild] The Defences page (WorksPages draws it): the town's danger out of a hundred and why, the wall's stage (an open
 * town, the palisade, stone, towers and a portcullis) and what the next stage needs; the wall's works, its sides and gates
 * (open or shut) with markers; the night watch's rota (each post, who keeps it on the first and the second watch, who is
 * on it now and with a lantern); the street patrols; and the night's log of handovers, sightings and bells.
 */
final class DefencesPage {

    private DefencesPage() {}

    static final String[] STAGES = { "Open", "Palisade", "Stone", "Towers" };

    static String build(WorksPages.Rows r, CompoundTag c) {
        if (c.isEmpty()) return "";
        int score = c.getInt("score"), stage = c.getInt("stage");
        // The danger.
        r.heading("The danger");
        int fill = score >= 52 ? UiStyle.DANGER_FILL : score >= 30 ? UiStyle.WARNING_FILL : score >= 12 ? UiStyle.INFO_FILL : UiStyle.SUCCESS_FILL;
        r.bar("Danger: " + c.getString("word"), score / 100F, fill, score + "/100");
        List<String> why = new ArrayList<>();
        for (Tag t : c.getList("reasons", Tag.TAG_STRING)) why.add(t.getAsString());
        r.text("Why: " + String.join("; ", why) + ".", UiStyle.TEXT_MUTED);
        int[] parts = c.getIntArray("parts");
        if (parts.length >= 4) {
            r.text("Raids " + parts[0] + " · monsters at night " + parts[1] + " · war " + parts[2] + " · wealth and size " + parts[3]
                + ". Raids count for thirty days, monsters for a week.", UiStyle.TEXT_FAINT);
        }
        // The stages, the one reached lit.
        List<String> chips = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        int[] at = c.getIntArray("at");
        for (int i = 0; i < STAGES.length; i++) {
            chips.add((i == stage ? "▶ " : "") + STAGES[i] + (i > 0 && at.length >= i ? " " + at[i - 1] : ""));
            tints.add(i <= stage ? (i == stage ? UiStyle.ACCENT_FILL : UiStyle.SUCCESS_FILL) : UiStyle.TILE_DISABLED);
        }
        r.chips(chips, tints);
        r.text("The wall's stage: " + c.getString("stageName") + "."
            + (c.getString("next").isEmpty() ? " The last stage." : " Next at " + c.getString("next") + ".")
            + " A stage once reached is kept.", UiStyle.TEXT);
        if (c.getBoolean("alarm")) r.text("The bell is ringing: " + c.getString("alarmWhy") + ". The gates are shut and the town is at the square.", UiStyle.DANGER);

        // The wall.
        r.heading("The wall");
        if (!c.getBoolean("live")) {
            r.text("The town wall is turned off on this server (the castleWalls setting).", UiStyle.TEXT_MUTED);
        } else {
            int[] w = c.getIntArray("wall");
            if (w.length >= 7 && w[1] > 0) {
                int pct = (int) (100L * w[0] / w[1]);
                r.bar(w[5] == 0 ? "Log palisade" : "Stone wall", WorksPages.frac(w[0], w[1]), w[5] == 0 ? 0xFF8B6A3E : UiStyle.ACCENT_FILL,
                    WorksPages.num(w[0]) + " of " + WorksPages.num(w[1]) + " (" + pct + "%)");
                r.text(w[3] + " towers, " + w[6] + " high" + (w[4] == 1 ? ", a portcullis over each gate" : "")
                    + (w[2] > 0 ? "; built round " + w[2] + " blocks of somebody's work on its line" : "") + ".", UiStyle.TEXT_MUTED);
            } else if (stage == 0) {
                r.text("No wall yet: the danger is " + c.getString("word") + ". The council raises a log palisade with its gates at danger "
                    + (at.length > 0 ? at[0] : 12) + ".", UiStyle.TEXT_MUTED);
            }
            for (Tag t : c.getList("lines", Tag.TAG_STRING)) r.text(t.getAsString(), UiStyle.TEXT_FAINT, 1);
            if (!c.getString("old").isEmpty()) r.text(c.getString("old"), UiStyle.INFO);
            r.text("The ring grows outward with the town: when the town takes a lot past a side, that side is rebuilt a ring further out and"
                + " the old line comes down, its ground given back to the lots. Gates stand on the radial roads.", UiStyle.TEXT_FAINT);
            String noWall = stage == 0 ? "there is no wall yet: the danger does not call for one." : "its ground is not loaded, or it is not planned yet.";
            for (Tag t : c.getList("sides", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) t;
                r.showMe(s.getString("name") + ": " + s.getInt("line") + " blocks from the heart" + (s.getInt("walk") > 0 ? ", its walk laid" : ""),
                    UiStyle.TEXT, WorksPages.at(s), s.getString("name").toLowerCase(java.util.Locale.ROOT),
                    "a stretch of its wall walk, where the night watch goes its round.", noWall);
            }
            ListTag gates = c.getList("gates", Tag.TAG_COMPOUND);
            if (!gates.isEmpty()) r.text(c.getString("gatesHours"), UiStyle.TEXT_MUTED);
            for (Tag t : gates) {
                CompoundTag gt = (CompoundTag) t;
                String state = !gt.getBoolean("hung") ? "not hung yet" : gt.getBoolean("shut") ? "shut" : "open";
                r.showMe(gt.getString("name") + ": " + state, gt.getBoolean("shut") ? UiStyle.WARNING : UiStyle.TEXT, WorksPages.at(gt),
                    gt.getString("name").toLowerCase(java.util.Locale.ROOT), "its gatehouse, where a guard keeps the gate by day and night.", noWall);
            }
        }

        // The night watch.
        CompoundTag watch = c.getCompound("watch");
        r.heading("The night watch");
        int phase = watch.getInt("phase");
        r.text((phase == 0 ? "Day: the gates are kept, the wall's posts stand empty till dusk." : phase == 1 ? "The first watch is on (dusk to midnight)."
            : "The second watch is on (midnight to dawn).") + " " + watch.getInt("walls") + " of the watch's " + watch.getInt("watch")
            + " are on the walls today (the roster on the Watch page).", UiStyle.TEXT);
        if (!watch.getBoolean("keeps")) {
            r.text("No town wall yet, so the night watch stands at the old posts and the gates, as the roster has it.", UiStyle.TEXT_MUTED);
        } else {
            r.text("Rounds on the wall walk and posts at the gates, lanterns in hand; a bell when trouble is seen; handed over at dusk, midnight and dawn.",
                UiStyle.TEXT_FAINT);
            ListTag rota = watch.getList("rota", Tag.TAG_COMPOUND);
            for (Tag t : rota) {
                CompoundTag p = (CompoundTag) t;
                String first = p.getString("first").isEmpty() ? "nobody" : p.getString("first");
                String second = p.getString("second").isEmpty() ? "nobody" : p.getString("second");
                String now = p.getString("now");
                String line = cap(p.getString("post")) + ": " + first + " / " + second
                    + (now.isEmpty() ? "" : " · now " + now + (p.getBoolean("lantern") ? " (lantern lit)" : "") + (p.getString("doing").isEmpty() ? "" : ", " + p.getString("doing")));
                WorksPages.Row row = r.showMe(line, now.isEmpty() ? UiStyle.TEXT_MUTED : UiStyle.SUCCESS, WorksPages.at(p), p.getString("post"),
                    "the night watch's post" + (p.getBoolean("wall") ? ": the round goes end to end along the walk." : ": the guard stands inside the gate."),
                    "the wall walk is not laid there yet.");
                row.tip = List.of(cap(p.getString("post")), "First watch (dusk to midnight): " + first, "Second watch (midnight to dawn): " + second,
                    now.isEmpty() ? "Nobody on it just now." : "On it now: " + now);
            }
            if (rota.isEmpty()) r.text("No posts yet: the gates are not planned.", UiStyle.TEXT_MUTED);
        }
        ListTag beats = watch.getList("beats", Tag.TAG_STRING);
        if (!beats.isEmpty()) {
            r.text("Street patrols (a lantern in hand after dark):", UiStyle.TEXT_MUTED);
            for (Tag t : beats) r.text(t.getAsString(), UiStyle.TEXT_FAINT, 1);
        }
        r.text("Tonight so far: " + watch.getInt("seen") + " seen, the bell rung " + watch.getInt("bells") + (watch.getInt("bells") == 1 ? " time." : " times."),
            UiStyle.TEXT_MUTED);
        ListTag log = watch.getList("log", Tag.TAG_STRING);
        r.heading("The night's log");
        if (log.isEmpty()) r.text("Nothing logged yet: the first watch goes on at dusk.", UiStyle.TEXT_MUTED);
        for (Tag t : log) {
            String s = t.getAsString();
            r.text(s, s.contains("bell") ? UiStyle.DANGER : s.contains("saw") ? UiStyle.WARNING : UiStyle.TEXT_FAINT);
        }
        return "Defences: danger " + score + "/100, " + STAGES[Math.max(0, Math.min(3, stage))].toLowerCase(java.util.Locale.ROOT)
            + (stage == 0 ? " town" : " stage");
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
