package com.jrpetty.mcassistant.pa;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [pa35] What a Player AI Assistant remembers of what its player told it, kept in its record (PaData.Rec, so it
 * survives saves, deaths and new bodies). One entry each: what kind of thing it is, what it is about, the sentence it
 * came from, and for a rule or a reminder, when and what. Places are the record's own waypoints (one list, so "go to
 * the farm" and the Memory tab agree). Pure data and pure lookups: PaMemoryParse reads sentences into entries,
 * PaMemoryAct carries them out.
 */
public final class PaMemory {
    // (saved and loaded by PaMemoryNbt, so this stays plain Java)

    private PaMemory() {}

    /** Every kind of thing it remembers. */
    public enum Kind {
        NAME, LIKE, DISLIKE, FAV, FACT, SELF,          // about its player (and itself)
        FRIEND, FOE, ONLY_ME, CLEAN,                   // people
        SPARE, STOCK, RULE,                            // standing rules
        REMINDER;                                      // reminders

        public Group group() {
            return switch (this) {
                case NAME, LIKE, DISLIKE, FAV, FACT, SELF -> Group.ME;
                case FRIEND, FOE, ONLY_ME, CLEAN -> Group.PEOPLE;
                case SPARE, STOCK, RULE -> Group.RULES;
                case REMINDER -> Group.REMINDERS;
            };
        }
    }

    /** The Memory tab's groups, in order. */
    public enum Group {
        ME("Me"), PLACES("Places"), PEOPLE("People"), RULES("Rules"), REMINDERS("Reminders");

        public final String label;

        Group(String label) { this.label = label; }
    }

    /** When a rule or reminder goes off. */
    public enum When {
        NONE(""), TIME("in time"), MORNING("every morning"), NIGHT("at nightfall"), DEATH("if you die"), LOW("when you're low"),
        HOME("when you get home"), RAIN("when it rains"), IDLE("when I'm free"), NEAR("when someone's about");

        public final String says;

        When(String says) { this.says = says; }

        public static When of(String s) {
            try { return valueOf(s); } catch (Exception e) { return NONE; }
        }
    }

    /** One thing remembered. */
    public static final class Entry {
        public int id;
        public Kind kind;
        /** What it is about, as said ("steak", "Sam", "mining", "blue flowers", "torches"). */
        public String subject = "";
        /** A second part: a favourite's category ("food"), a fact's value, a rule's order, a reminder's words. */
        public String value = "";
        /** Extra detail: a rule's or reminder's trigger, a spared block list, a like's category, a friend's leave to give orders. */
        public When when = When.NONE;
        public String extra = "";
        /** A number: how many to keep (STOCK), a reminder's delay in ticks (as read), +1/-1 for an opinion (SELF). */
        public int n;
        /** A reminder's game time when due (-1: on its trigger). */
        public long due = -1;
        /** The sentence it came from (shown in the Memory tab, and what an edit starts from). */
        public String said = "";
        public long made;

        public Entry() {}

        public Entry(Kind kind, String subject, String value) {
            this.kind = kind;
            this.subject = subject == null ? "" : subject;
            this.value = value == null ? "" : value;
        }

        public Entry when(When w) { this.when = w; return this; }

        public Entry extra(String e) { this.extra = e == null ? "" : e; return this; }

        public Entry n(int v) { this.n = v; return this; }

        /** The subject as a key: lower case, single spaces, a plural's s gone. */
        public String key() {
            return key(subject);
        }

        public static String key(String s) {
            String k = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _]", " ").replaceAll("\\s+", " ").trim();
            return k;
        }

        /** Does this entry say the same thing as that one (so a new one replaces it rather than doubling up)? */
        public boolean same(Entry o) {
            if (o == null) return false;
            return switch (kind) {
                case NAME, ONLY_ME -> o.kind == kind;
                case FAV -> o.kind == Kind.FAV && value.equalsIgnoreCase(o.value);
                case LIKE, DISLIKE -> (o.kind == Kind.LIKE || o.kind == Kind.DISLIKE) && stem(key()).equals(stem(o.key()));
                case FRIEND, FOE -> (o.kind == Kind.FRIEND || o.kind == Kind.FOE) && key().equals(o.key());
                case SELF -> o.kind == Kind.SELF && key().equals(o.key());
                case STOCK, SPARE -> o.kind == kind && stem(key()).equals(stem(o.key()));
                case CLEAN -> o.kind == kind && key().equals(o.key());
                case RULE -> o.kind == kind && when == o.when && value.equalsIgnoreCase(o.value) && extra.equalsIgnoreCase(o.extra);
                case REMINDER -> false;
                case FACT -> o.kind == kind && key().equals(o.key());
            };
        }

        /** One line for the Memory tab and for "what do you remember?". */
        public String line() {
            return switch (kind) {
                case NAME -> "Call you " + subject;
                case LIKE -> "You like " + subject + (extra.startsWith("over:") ? " (not " + extra.substring(5) + ")" : "");
                case DISLIKE -> "You can't stand " + subject;
                case FAV -> "Your favourite " + value + ": " + subject;
                case FACT -> value.isEmpty() ? cap(subject) : cap(subject) + ": " + value;
                case SELF -> subject.equals("name") ? "Its name: " + value + " (you named it)"
                    : (n < 0 ? "You say it's rubbish at " : "You say it's good at ") + subject;
                case FRIEND -> subject + " is your friend" + (extra.contains("help") ? "; it helps them" : "") + (extra.contains("orders") ? " and takes their orders" : "");
                case FOE -> subject + ": not to be trusted";
                case ONLY_ME -> "Orders from you only";
                case CLEAN -> "No swearing around " + subject;
                case SPARE -> (extra.equals("#animals") ? "Never hurt " : "Never break ") + subject;   // [pa35] cows are hurt, not broken
                case STOCK -> "Always keep " + n + " " + subject;
                case RULE -> cap(when == When.NEAR ? "when " + extra + " is about" : when.says) + ": " + value;
                case REMINDER -> "Remind you" + (value.isEmpty() ? "" : " to " + value);
            };
        }

    }

    /** A plural's s off ("creepers" and "creeper" are the same dislike). */
    public static String stem(String k) {
        if (k.matches("(?:.* )?(?:zombies|cookies|pies|movies|ties|lies|brownies|hoodies|rookies|budgies|selfies)$")) return k.substring(0, k.length() - 1);
        if (k.endsWith("ies") && k.length() > 4) return k.substring(0, k.length() - 3) + "y";
        if (k.endsWith("ches") || k.endsWith("shes") || k.endsWith("xes")) return k.substring(0, k.length() - 2);
        if (k.endsWith("s") && !k.endsWith("ss") && k.length() > 3) return k.substring(0, k.length() - 1);
        return k;
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the book (one per record)

    /** One assistant's memories: the entries, the next id, whether it is taking notes. */
    public static final class Book {
        public final List<Entry> entries = new ArrayList<>();
        public int next = 1;
        public boolean paused;
        /** The id of the last thing it was told, for "forget that" (not saved). */
        public int lastId = -1;
        public long lastAt = -1;

        public int size() { return entries.size(); }

        /** Add (or replace the same thing said before); returns the entry kept. */
        public Entry put(Entry e, long now) {
            Entry old = null;
            for (Entry o : entries) if (e.same(o)) { old = o; break; }
            if (old != null) entries.remove(old);
            e.id = old != null ? old.id : next++;
            e.made = now;
            entries.add(e);
            lastId = e.id;
            lastAt = now;
            return e;
        }

        @Nullable
        public Entry byId(int id) {
            for (Entry e : entries) if (e.id == id) return e;
            return null;
        }

        public boolean remove(int id) {
            return entries.removeIf(e -> e.id == id);
        }

        public List<Entry> of(Kind... kinds) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries) for (Kind k : kinds) if (e.kind == k) { out.add(e); break; }
            return out;
        }

        @Nullable
        public Entry first(Kind k) {
            for (Entry e : entries) if (e.kind == k) return e;
            return null;
        }

        /** Everything that mentions this (a person, a thing, a place word), for "forget everything about Sam". */
        public List<Entry> about(String what) {
            String k = stem(Entry.key(what));
            List<Entry> out = new ArrayList<>();
            if (k.isEmpty()) return out;
            for (Entry e : entries) {
                String s = stem(e.key()), v = stem(Entry.key(e.value)), x = Entry.key(e.extra);
                if (s.equals(k) || v.equals(k) || (" " + Entry.key(e.subject) + " ").contains(" " + k + " ")
                    || (" " + Entry.key(e.value) + " ").contains(" " + k + " ") || x.equals(k)) out.add(e);
            }
            return out;
        }

        /** The favourite of a kind ("food"), or null. */
        @Nullable
        public String favourite(String category) {
            for (Entry e : entries) if (e.kind == Kind.FAV && e.value.equalsIgnoreCase(category)) return e.subject;
            return null;
        }

        /** Is this one of its player's friends (or foes)? By in-game name, any case. */
        public boolean friend(String name) {
            for (Entry e : entries) if (e.kind == Kind.FRIEND && e.subject.equalsIgnoreCase(name)) return true;
            return false;
        }

        public boolean foe(String name) {
            for (Entry e : entries) if (e.kind == Kind.FOE && e.subject.equalsIgnoreCase(name)) return true;
            return false;
        }

        @Nullable
        public Entry person(String name) {
            for (Entry e : entries) if ((e.kind == Kind.FRIEND || e.kind == Kind.FOE) && e.subject.equalsIgnoreCase(name)) return e;
            return null;
        }

        public boolean onlyMe() {
            return first(Kind.ONLY_ME) != null;
        }

    }
}
