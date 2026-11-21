package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.LibraryRecords;
import com.jrpetty.mcassistant.village.Tales;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [individual] Where a folk came from and what its life has been: told when a player asks "Tell me about yourself",
 * and kept in the library's lives (Authors) when the town writes one.
 *
 * <p>A founder came from somewhere before the town was: a hamlet whose name it gives, on the kind of land its town
 * stands on, raised by somebody it names. A child of the town was born here, to the parents the town knows. The
 * rest is its own life as it was lived, out of what it remembers (Persona's weightiest memories), the marks it bears
 * and how it came by them, the fears it got over, the dream that came true, the keepsake it carries.
 */
public final class Backstory {

    private Backstory() {}

    private static final String[] HAMLETS = {"Applecross", "Brackenford", "Cold Ashby", "Dunmere", "Eastwick", "Fallowfield",
        "Greystones", "Hollin", "Ivybridge", "Kettlewell", "Long Marton", "Millbeck", "Nettleham", "Oakhanger", "Pennyholt",
        "Quarry End", "Rookhope", "Saltcote", "Thornby", "Underbarrow", "Wethercote", "Yarrowford"};
    private static final String[] WHERE = {"up in the hills", "down by the river", "out on the plains", "in the deep woods",
        "on the coast", "past the marshes", "under the mountains", "a long week's walk to the south"};
    private static final String[] RAISED = {"by its grandmother, who kept bees", "by an uncle who was a miller", "by its mother alone",
        "by the blacksmith's family, after its own were lost", "by two fishers and a dog", "by its father, a shepherd",
        "by an aunt who taught it its letters", "by a big, loud family of nine", "by the village as a whole, it says"};
    private static final String[] BOOKS = {"The Farmer's Almanac", "Tales of the Nether", "A Field Guide to Birds", "The Old Roads",
        "Lord of the Deep Mines", "The Lighthouse Keeper", "Songs of the Plains", "A Hundred Ways with Wheat",
        "The Long Winter", "Of Stars and Stargazers", "The Smith's Apprentice", "The Map Without Edges"};

    static String birthplace(VillageFolkEntity f, RandomSource r) {
        return "born in " + HAMLETS[r.nextInt(HAMLETS.length)] + ", " + WHERE[r.nextInt(WHERE.length)];
    }

    static String raisedBy(RandomSource r) {
        return RAISED[r.nextInt(RAISED.length)];
    }

    /** A book it loves: one its town's library holds if it has any, else an old favourite. */
    static String favouriteBook(VillageFolkEntity f, RandomSource r) {
        UUID village = f.ownerId();
        if (village != null && LibraryRecords.has(village)) {
            List<String> titles = new ArrayList<>();
            for (LibraryRecords.Title t : LibraryRecords.shelf(village).books) if (!t.superseded && !t.title.isEmpty()) titles.add(t.title);
            if (!titles.isEmpty() && r.nextBoolean()) return titles.get(r.nextInt(titles.size()));
        }
        return BOOKS[r.nextInt(BOOKS.length)];
    }

    /** Its story, in its own words, for "Tell me about yourself". */
    static String tell(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        if (!s.rolled) return "";
        StringBuilder sb = new StringBuilder();
        if (!s.born.isEmpty()) {
            sb.append("I was ").append(s.born);
            if (!s.raised.isEmpty()) sb.append(", raised ").append(s.raised.replace(" its ", " my ").replace("its ", "my "));
            sb.append(". ");
        }
        for (String line : moments(f, 3)) sb.append(line).append(' ');
        if (s.scar > 0) sb.append("This scar? I got it ").append(s.scarHow).append(". ");
        if (s.patch > 0) sb.append("Lost the eye ").append(s.scarHow).append(". ");
        if (!s.overcome.isEmpty()) sb.append("I used to be afraid of ").append(s.overcome.get(s.overcome.size() - 1).replaceFirst(", on day \\d+", ""))
            .append(", but not any more. ");
        if (s.keepsakeDay >= 0) sb.append("I always carry ").append(s.keepsake.word).append(" — ")
            .append(s.keepsakeStory.replace(" it ", " I ").replace("its ", "my ")).append(". ");
        if (s.literate && !s.book.isEmpty()) sb.append("My favourite book is ").append(s.book).append(". ");
        else if (!s.literate && !f.isBaby()) sb.append("I never learned to read, mind. ");
        return sb.toString().trim();
    }

    /** The big moments of its life, the weightiest it remembers, in its own words, oldest first. */
    static List<String> moments(VillageFolkEntity f, int most) {
        List<Persona.Memory> mem = new ArrayList<>(f.persona().memories());
        mem.removeIf(m -> m.weight() < 5);
        mem.sort((a, b) -> b.weight() - a.weight());
        if (mem.size() > most) mem = mem.subList(0, most);
        mem.sort((a, b) -> Long.compare(a.day(), b.day()));
        List<String> out = new ArrayList<>();
        for (Persona.Memory m : mem) {
            String t = m.text();
            out.add("On day " + (m.day() + 1) + ", " + (t.startsWith("I ") || t.startsWith("I'") ? t : "I remember: " + t) + ".");
        }
        return out;
    }

    /** For the library's life of this folk (Authors.life), in its own words: where it came from, its scar, the fear
     *  it got over, its keepsake. */
    static List<Tales.Memory> lifeMemories(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        List<Tales.Memory> out = new ArrayList<>();
        if (!s.rolled) return out;
        long since = f.persona().since() < 0 ? 0 : f.persona().since();
        if (!s.born.isEmpty()) out.add(new Tales.Memory(f.bornDay() != VillageFolkEntity.UNKNOWN && f.bornDay() >= 0 ? f.bornDay() : since,
            "I was " + s.born + (s.raised.isEmpty() ? "" : ", raised " + mine(s.raised))));
        if (s.scar > 0 && s.scarDay >= 0) out.add(new Tales.Memory(s.scarDay, "I got this scar " + s.scarHow));
        if (s.keepsakeDay >= 0) out.add(new Tales.Memory(s.keepsakeDay, "I always carry " + s.keepsake.word + ": " + mine(s.keepsakeStory)));
        return out;
    }

    private static String mine(String s) {
        return s.replace(" it ", " I ").replace("its ", "my ");
    }
}
