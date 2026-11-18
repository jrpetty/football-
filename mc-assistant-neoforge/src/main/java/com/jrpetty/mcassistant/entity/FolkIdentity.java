package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.DyeColor;

import java.util.List;
import java.util.Locale;

/**
 * [ui] What makes one folk look and read like itself on its screens, all of it from what the game already keeps on it
 * (nothing made up for the screen):
 * <ul>
 * <li><b>its colour</b>: the main colour of its clothes when it has chosen a style (Style), else the colour it loves
 *     (Decor.colour, from what it loves and its hobby), the band across its profile and its talk screen;</li>
 * <li><b>its saying</b>: a line it lives by, from what it cares for most (Values) turned by its first trait, so two
 *     folk who care for the same thing still say it their own way;</li>
 * <li><b>its chips</b>: its two traits, the kind of folk it is, its hobby, its season, a reader, left-handed, an elder;</li>
 * <li><b>its own life</b>: its dream, fears, habits, favourite place, keepsake, reading and favourites (Individual's
 *     card lines), where it was born and raised.</li>
 * </ul>
 * Built with the profile (FolkProfile "self") and for the talk screen's context (its colour), never on a tick.
 */
public final class FolkIdentity {

    private FolkIdentity() {}

    /** Its colour: its clothes' main dye when it has a style, else the colour it loves. */
    public static DyeColor colour(VillageFolkEntity f) {
        f.ensurePersona();
        return FireworksMaker.colourOf(f);
    }

    /** Its colour as RGB (no alpha), for a screen's band. */
    public static int rgb(VillageFolkEntity f) {
        return colour(f).getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** The sayings, three for each thing a folk can care for most (Values.Value order), in its own voice. */
    private static final String[][] SAYINGS = {
        { "A full larder is a quiet house.", "Feed them first; talk after.", "Sow early, reap proud." },
        { "Every family wants a roof.", "Four walls and a warm hearth.", "A house goes up one stone a day." },
        { "There's always a better way.", "The next age won't wait for us.", "Learn one new thing a day." },
        { "Bar the gate, then sleep.", "Watch the dark and keep the peace.", "Better a wall today than a grave tomorrow." },
        { "A fair coin for fair work.", "Trade makes friends of strangers.", "Count twice, spend once." },
        { "Work's done; now the song.", "Life's too short for long faces.", "A day without a dance is a day lost." },
        { "As our grandmothers did it.", "The old ways are the good ways.", "Mind the bell and keep the feast." } };

    /** The line it lives by: what it cares for most, said the way its first trait would say it. */
    public static String saying(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        f.ensurePersona();
        Values.Value v = Values.top(f);
        String[] lines = SAYINGS[Math.min(SAYINGS.length - 1, v.ordinal())];
        List<Social.Trait> traits = f.life().traits();
        int turn = traits.isEmpty() ? 0 : traits.get(0).ordinal();
        return lines[Math.floorMod(f.getUUID().hashCode() + turn, lines.length)];
    }

    /** The profile's "self": colour, saying, chips and its own life. */
    public static CompoundTag tag(VillageFolkEntity f) {
        Individual.ensure(f);
        f.ensurePersona();
        CompoundTag t = new CompoundTag();
        DyeColor c = colour(f);
        t.putInt("colour", c.getTextureDiffuseColor() & 0xFFFFFF);
        t.putString("colour_word", Decor.colourWord(c));
        t.putString("saying", saying(f));
        ListTag chips = new ListTag();
        for (Social.Trait tr : f.life().traits()) chip(chips, tr.title(), "trait");
        if (!f.isBaby()) chip(chips, Values.top(f).type, "kind");
        Persona me = f.persona();
        if (me.rolled()) chip(chips, cap(me.hobby().name().toLowerCase(Locale.ROOT).replace('_', ' ')), "hobby");
        Individual.Self s = f.individual();
        if (s.rolled) {
            chip(chips, "Loves " + s.season.word, "season");
            if (s.literate && !f.isBaby()) chip(chips, "Reader", "mark");
            if (s.genes.leftHanded) chip(chips, "Left-handed", "mark");
        }
        if (f.isElder()) chip(chips, "Elder", "mark");
        t.put("chips", chips);
        ListTag habits = new ListTag();
        if (s.rolled) for (Habits.Habit h : s.habits) habits.add(net.minecraft.nbt.StringTag.valueOf(cap(h.word)));
        t.put("habits", habits);
        ListTag own = new ListTag();
        for (String[] line : Individual.cardLines(f)) {
            CompoundTag l = new CompoundTag();
            l.putString("k", line[0]);
            l.putString("v", line[1]);
            own.add(l);
        }
        t.put("own", own);
        t.putString("born", s.born == null ? "" : s.born);
        t.putString("raised", s.raised == null ? "" : s.raised);
        return t;
    }

    private static void chip(ListTag out, String text, String kind) {
        if (text == null || text.isBlank()) return;
        CompoundTag c = new CompoundTag();
        c.putString("t", text);
        c.putString("k", kind);
        out.add(c);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
