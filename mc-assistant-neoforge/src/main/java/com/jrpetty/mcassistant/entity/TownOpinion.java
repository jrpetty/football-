package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featfolk] What a town makes of a player, from its witnesses: the folk who saw what the player did, or dealt with
 * them. Its reasons are theirs, by name ("Saw you defend the square from a raid: Bramble, Fen and 2 more"; "Says you
 * tried to short them on wheat: Hale"), and word passed on from a witness is counted at half, under the witness's name.
 * A folk who never met the player and heard nothing from one who did adds nothing to the reasons.
 *
 * <p>It weighs in the town's {@link Standing} too (the title that sets prices, greetings and the work offered): a
 * witness's opinion counts four times, a folk's that has it from a witness twice, anyone else's (the town's word on
 * the board, a vote) once ({@link #weight}).
 */
public final class TownOpinion {

    private TownOpinion() {}

    /** One reason: what was done, who saw it or had it done to them, and how many only heard of it. */
    public record Reason(String text, int weight, List<String> witnesses, int heard, boolean good) {}

    /** The town's view: its score from its witnesses, how many there are, how many only heard, and the reasons. */
    public record View(int score, int witnesses, int heard, List<Reason> reasons) {}

    /** How much this folk's opinion of the player counts in its town's: 4 a witness, 2 on a witness's word, 1 else. */
    public static int weight(VillageFolkEntity f, UUID player) {
        if (Witness.contact(f, player)) return 4;
        return Witness.heardOf(f, player) ? 2 : 1;
    }

    public static View of(UUID village, UUID player) {
        int sum = 0, weights = 0, witnesses = 0, heard = 0;
        Map<String, Object[]> by = new LinkedHashMap<>();     // key -> {deed, witnesses list, heard count[]}
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !f.persona().rolled() || f.isShowcase()) continue;
            boolean contact = Witness.contact(f, player), told = !contact && Witness.heardOf(f, player);
            if (contact) witnesses++;
            else if (told) heard++;
            if (contact || told) {
                int w = contact ? 2 : 1;
                sum += f.persona().affinity(player) * w;
                weights += w;
            }
            for (FolkLines.Deed d : f.lines().deeds) {
                if (!d.player.equals(player) || !d.kind.notable()) continue;
                // Who it is the word of: the folk itself, or the witness it heard it from; and whether that one had it
                // done to it ("Says you...") or saw it done ("Saw you...").
                String teller = d.firstHand() ? f.displayNameCap() : d.sourceName;
                boolean done = d.victim.equals(teller);
                String key = d.kind + "|" + d.detail + "|" + d.victim + "|" + done;
                Object[] row = by.computeIfAbsent(key, k -> new Object[]{ d, new ArrayList<String>(), new int[1], teller });
                @SuppressWarnings("unchecked") List<String> names = (List<String>) row[1];
                if (d.firstHand()) {
                    if (!names.contains(f.displayNameCap())) names.add(f.displayNameCap());
                } else ((int[]) row[2])[0]++;
            }
        }
        List<Reason> reasons = new ArrayList<>();
        for (Object[] row : by.values()) {
            FolkLines.Deed d = (FolkLines.Deed) row[0];
            @SuppressWarnings("unchecked") List<String> names = (List<String>) row[1];
            int h = ((int[]) row[2])[0];
            // Hearsay whose witness is no longer about still names them, as it was told.
            if (names.isEmpty()) names.add((String) row[3]);
            int w = d.kind.weight * names.size() + d.kind.weight * h / 2;
            reasons.add(new Reason(Witness.reason(d, names.get(0)), w, names, h, d.kind.weight >= 0));
        }
        reasons.sort((x, y) -> Integer.compare(Math.abs(y.weight()), Math.abs(x.weight())));
        return new View(weights == 0 ? 0 : sum / weights, witnesses, heard, reasons);
    }

    /** "Bramble", "Bramble and Fen", "Bramble, Fen and 3 more". */
    static String names(List<String> n) {
        if (n.isEmpty()) return "";
        if (n.size() == 1) return n.get(0);
        if (n.size() == 2) return n.get(0) + " and " + n.get(1);
        return n.get(0) + ", " + n.get(1) + " and " + (n.size() - 2) + " more";
    }

    /** One reason as a line: "Saw you defend the square from a raid: Bramble, Fen and 2 more (3 heard)". */
    public static String line(Reason r) {
        String who = names(r.witnesses());
        return r.text() + (who.isEmpty() ? "" : ": " + who) + (r.heard() > 0 ? " (" + r.heard() + " heard it from them)" : "");
    }

    /** For the board (OpinionPage): the town's title for the player and why, from its witnesses. */
    public static CompoundTag report(UUID village, UUID player, long gameTime) {
        CompoundTag c = new CompoundTag();
        Standing.View s = Standing.of(village, player, gameTime);
        View v = of(village, player);
        c.putString("town", Villages.name(village));
        c.putString("title", s.title().words);
        c.putInt("standing", s.score());
        c.putInt("score", v.score());
        c.putInt("witnesses", v.witnesses());
        c.putInt("heard", v.heard());
        c.putInt("known", s.knownBy());
        // The respect rating: the town's standing score (witnesses weighed first), named, with its reasons in short.
        c.putInt("respect", s.score());
        String rating = rating(s.score(), v.witnesses() + v.heard());
        c.putString("rating", rating);
        List<String> why = new ArrayList<>();
        for (Reason r : v.reasons()) {
            if (why.size() >= 3) break;
            String t = r.text();
            why.add(t.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + t.substring(1));
        }
        c.putString("reasons", why.isEmpty() ? "" : rating + ": " + String.join("; ", why) + ".");
        ListTag good = new ListTag(), bad = new ListTag();
        for (Reason r : v.reasons()) {
            if (good.size() + bad.size() >= 24) break;
            (r.good() ? good : bad).add(StringTag.valueOf(line(r)));
        }
        c.put("good", good);
        c.put("bad", bad);
        ListTag effects = new ListTag();
        for (String e : effects(s.title())) effects.add(StringTag.valueOf(e));
        c.put("effects", effects);
        return c;
    }

    /** The respect rating's name for a score: Unknown with nobody who knows the player, else Despised to Revered. */
    public static String rating(int score, int knownBy) {
        if (knownBy == 0 && score == 0) return "Unknown";
        if (score <= -50) return "Despised";
        if (score <= -20) return "Distrusted";
        if (score < -5) return "Doubted";
        if (score < 10) return "Noticed";
        if (score < 30) return "Accepted";
        if (score < 55) return "Respected";
        if (score < 80) return "Esteemed";
        return "Revered";
    }

    /** The town's respect for the player, named ("Respected"), from its standing and its witnesses. */
    public static String ratingOf(UUID village, UUID player, long gameTime) {
        Standing.View s = Standing.of(village, player, gameTime);
        View v = of(village, player);
        return rating(s.score(), v.witnesses() + v.heard());
    }

    /** What the title does for the player here, in a few lines. */
    static List<String> effects(Standing.Title t) {
        List<String> out = new ArrayList<>();
        switch (t) {
            case OUTCAST -> { out.add("Nobody will trade with you, or talk past a word or two."); out.add("No work is offered you here."); }
            case UNWELCOME -> { out.add("Prices are doubled for you."); out.add("Greetings are short."); }
            case STRANGER, VISITOR -> { out.add("Prices are as they are."); out.add("Folk are civil, and making up their minds."); }
            case FRIEND -> { out.add("A little extra thrown in on a trade."); out.add("Readier favours, and work offered."); }
            case HONOURED -> { out.add("A house may be built for you."); out.add("The better contracts come to you first."); }
            case HERO -> { out.add("Your name on the town's monument."); out.add("Every door open."); }
        }
        return out;
    }
}
