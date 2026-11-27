package com.jrpetty.mcassistant.village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [admin] What follows an admin action's words, parsed: the positional values, the {@code town:} and {@code folk:}
 * selectors, and the {@code --flags}. Quoted values keep their spaces ({@code folk:"Ada Brook"}). Checked against the
 * action's parameters (AdminSpec): a count in its range, a word among its choices; the first fault is the error.
 *
 * <p>Plain Java, so the unit tests and the client's panel read it too.
 */
public final class AdminArgs {

    /** A town selector: by name or id, the nearest, or all of them. */
    public record TownSel(Kind kind, String value) {
        public enum Kind { NEAREST, ALL, NAMED }

        public static final TownSel NEAREST = new TownSel(Kind.NEAREST, "");
    }

    /** A folk selector: by name or uuid, every folk of a trade (@smith), or everybody (@all). */
    public record FolkSel(Kind kind, String value) {
        public enum Kind { ALL, TRADE, NAMED }

        public static final FolkSel EVERYONE = new FolkSel(Kind.ALL, "");
    }

    private final List<String> positional;
    private final Set<String> flags;
    private final TownSel town;
    private final FolkSel folk;
    private final boolean folkGiven;
    @Nullable private final String error;

    private AdminArgs(List<String> positional, Set<String> flags, TownSel town, FolkSel folk, boolean folkGiven, @Nullable String error) {
        this.positional = positional;
        this.flags = flags;
        this.town = town;
        this.folk = folk;
        this.folkGiven = folkGiven;
        this.error = error;
    }

    public static final AdminArgs NONE = new AdminArgs(List.of(), Set.of(), TownSel.NEAREST, FolkSel.EVERYONE, false, null);

    /** Split a line into words, a quoted stretch ("like this" or 'this') kept as one with its quotes taken off. */
    public static List<String> tokens(@Nullable String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0 || any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }

    /** Parse a town selector's value: "nearest", "all", or a name or id. */
    public static TownSel town(String v) {
        String s = v.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("nearest") || s.equalsIgnoreCase("here")) return TownSel.NEAREST;
        if (s.equalsIgnoreCase("all") || s.equals("*")) return new TownSel(TownSel.Kind.ALL, "");
        return new TownSel(TownSel.Kind.NAMED, s);
    }

    /** Parse a folk selector's value: "@all", "@<trade>", or a name or uuid. */
    public static FolkSel folk(String v) {
        String s = v.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("@all") || s.equals("@a") || s.equals("*")) return FolkSel.EVERYONE;
        if (s.startsWith("@")) return new FolkSel(FolkSel.Kind.TRADE, s.substring(1).toLowerCase(Locale.ROOT));
        return new FolkSel(FolkSel.Kind.NAMED, s);
    }

    /** Parse without checking against any action. */
    public static AdminArgs parse(@Nullable String raw) {
        return parse(raw, null);
    }

    /** Parse, and check the positional values against this action's parameters (when given). */
    public static AdminArgs parse(@Nullable String raw, @Nullable AdminSpec.Action action) {
        List<String> pos = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        TownSel town = TownSel.NEAREST;
        FolkSel folk = FolkSel.EVERYONE;
        boolean folkGiven = false;
        String error = null;
        for (String t : tokens(raw)) {
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("--")) flags.add(low);
            else if (low.startsWith("town:")) town = town(t.substring(5));
            else if (low.startsWith("folk:")) {
                folk = folk(t.substring(5));
                folkGiven = true;
            } else pos.add(t);
        }
        if (action != null) error = check(action, pos);
        return new AdminArgs(Collections.unmodifiableList(pos), Collections.unmodifiableSet(flags), town, folk, folkGiven, error);
    }

    /** The first thing wrong with these values for this action, or null. */
    @Nullable
    static String check(AdminSpec.Action action, List<String> pos) {
        List<AdminSpec.Param> ps = action.params();
        for (int i = 0; i < ps.size(); i++) {
            AdminSpec.Param p = ps.get(i);
            if (i >= pos.size()) {
                if (!p.optional()) return "Missing " + p.usage() + ". Usage: " + action.usage();
                continue;
            }
            String v = pos.get(i);
            switch (p.kind()) {
                case INT -> {
                    Integer n = integer(v);
                    if (n == null) return p.name() + " must be a whole number, not \"" + v + "\".";
                    if (n < p.min() || n > p.max()) return p.name() + " must be " + p.min() + " to " + p.max() + " (at most " + p.max() + " in one go).";
                }
                case WORD -> {
                    if (!p.choices().isEmpty() && !p.choices().contains(v.toLowerCase(Locale.ROOT))) {
                        return p.name() + " must be one of " + String.join(", ", p.choices()) + ".";
                    }
                }
                case AGE -> {
                    if (!AdminSpec.AGES.contains(v.toLowerCase(Locale.ROOT))) return "No age called " + v + ". One of " + String.join(", ", AdminSpec.AGES) + ".";
                }
                case TIER -> {
                    if (!AdminSpec.TIERS.contains(v.toLowerCase(Locale.ROOT))) return "No tier called " + v + ". One of " + String.join(", ", AdminSpec.TIERS) + ".";
                }
                case PHASE -> {
                    if (!AdminSpec.PHASES.contains(v.toLowerCase(Locale.ROOT))) return "No phase called " + v + ". One of " + String.join(", ", AdminSpec.PHASES) + ".";
                }
                case SCENARIO -> {
                    if (!AdminSpec.SCENARIOS.contains(v.toLowerCase(Locale.ROOT))) return "No scenario called " + v + ". One of " + String.join(", ", AdminSpec.SCENARIOS) + ".";
                }
                case EVENT -> {
                    if (!AdminSpec.EVENTS.contains(v.toLowerCase(Locale.ROOT))) return "No event called " + v + ". One of " + String.join(", ", AdminSpec.EVENTS) + ".";
                }
                default -> { }
            }
        }
        if (pos.size() > ps.size() && (ps.isEmpty() || ps.get(ps.size() - 1).kind() != AdminSpec.Kind.TEXT)) {
            return "Too many values (\"" + pos.get(ps.size()) + "\"). Usage: " + action.usage();
        }
        return null;
    }

    @Nullable
    private static Integer integer(String v) {
        try {
            return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reading

    /** The first fault found, or null when the values fit the action. */
    @Nullable
    public String error() { return error; }

    public List<String> positional() { return positional; }

    public TownSel town() { return town; }

    public FolkSel folk() { return folk; }

    /** Was a folk: selector given at all (folk actions with none act on everyone in the town)? */
    public boolean folkGiven() { return folkGiven; }

    public boolean flag(String f) { return flags.contains(f); }

    public boolean dryRun() { return flag(AdminSpec.DRY_RUN); }

    /** The i-th value, or this fallback. */
    public String word(int i, String fallback) {
        return i < positional.size() ? positional.get(i) : fallback;
    }

    /** The i-th value as a number, or this fallback. */
    public int num(int i, int fallback) {
        Integer n = i < positional.size() ? integer(positional.get(i)) : null;
        return n == null ? fallback : n;
    }

    /** Everything from the i-th value on, joined (a TEXT parameter). */
    public String rest(int i) {
        return i >= positional.size() ? "" : String.join(" ", positional.subList(i, positional.size()));
    }
}
