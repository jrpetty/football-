package com.jrpetty.mcassistant.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * [player15] One of the player's pages (PlayerLife), as the server writes it and the client draws it (client/LifeScreen):
 * a title and a line under it, tabs, and the page's blocks top to bottom: headings, paragraphs, rows (an icon, what it is,
 * a line under it, a figure on the right, and its buttons), bars, choices (a button that cycles through the options, so
 * nothing is typed), a text box where a name is wanted, and a small map of places to pick from. A button carries its
 * action and arguments; the choices and text boxes of the page go with every button under "f".
 */
public final class LifePage {

    private final CompoundTag t = new CompoundTag();
    private final ListTag blocks = new ListTag();
    private final ListTag tabs = new ListTag();
    @Nullable private final HolderLookup.Provider reg;

    public LifePage(String page, String title, String sub, @Nullable HolderLookup.Provider reg) {
        t.putString("page", page);
        t.putString("title", title);
        t.putString("sub", sub);
        this.reg = reg;
    }

    public LifePage tab(String id, String label, boolean on) {
        CompoundTag x = new CompoundTag();
        x.putString("id", id);
        x.putString("l", label);
        x.putBoolean("on", on);
        tabs.add(x);
        return this;
    }

    public LifePage heading(String text) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "h");
        x.putString("text", text);
        blocks.add(x);
        return this;
    }

    public LifePage text(String text) {
        return text(text, 0);
    }

    /** A paragraph; colour 0 is the ordinary ink, else 1 good, 2 a warning, 3 bad, 4 faint. */
    public LifePage text(String text, int colour) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "p");
        x.putString("text", text);
        x.putInt("c", colour);
        blocks.add(x);
        return this;
    }

    /**
     * [wpb] A framed box: a title band and plain lines under it (a job's "How you're paid", a first-time hint, a question
     * before money moves). Colour as for a paragraph: 0 plain, 1 good, 2 a warning, 3 bad, 4 for information.
     */
    public LifePage box(String title, java.util.List<String> lines, int colour) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "box");
        x.putString("title", title);
        ListTag l = new ListTag();
        for (String s : lines) l.add(net.minecraft.nbt.StringTag.valueOf(s));
        x.put("lines", l);
        x.putInt("c", colour);
        blocks.add(x);
        return this;
    }

    public LifePage bar(String label, double frac, String right) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "bar");
        x.putString("l", label);
        x.putFloat("f", (float) Math.max(0, Math.min(1, frac)));
        x.putString("r", right);
        blocks.add(x);
        return this;
    }

    /** A text box (a name): its id in "f", its label, what is in it, its longest. */
    public LifePage field(String id, String label, String value, int max) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "field");
        x.putString("id", id);
        x.putString("l", label);
        x.putString("v", value);
        x.putInt("max", max);
        blocks.add(x);
        return this;
    }

    /** A choice: a button that steps through the options; the chosen value goes in "f" under its id. */
    public LifePage choice(String id, String label, String[] values, String[] labels, String chosen) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "choice");
        x.putString("id", id);
        x.putString("l", label);
        ListTag o = new ListTag();
        for (int i = 0; i < values.length; i++) {
            CompoundTag c = new CompoundTag();
            c.putString("v", values[i]);
            c.putString("l", labels[i]);
            o.add(c);
        }
        x.put("opts", o);
        x.putString("v", chosen);
        blocks.add(x);
        return this;
    }

    /** A row; add its buttons with {@link Row#button}. */
    public Row row(@Nullable ItemStack icon, String label, String sub, String right) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "row");
        if (icon != null && !icon.isEmpty() && reg != null) x.put("icon", icon.save(reg));
        x.putString("l", label);
        x.putString("s", sub);
        x.putString("r", right);
        ListTag b = new ListTag();
        x.put("b", b);
        blocks.add(x);
        return new Row(x, b);
    }

    /** A row of buttons on its own. */
    public Row buttons() {
        return row(null, "", "", "");
    }

    /** A small map: the places in it, each a dot with its label, chosen or not, and its action when clicked. */
    public Map map(String label) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "map");
        x.putString("l", label);
        ListTag p = new ListTag();
        x.put("pts", p);
        blocks.add(x);
        return new Map(x, p);
    }

    /** A picture of a building, from its drawing's blocks (x, y, z, block state id): turned with the mouse on the screen. */
    public LifePage picture(String key, int[] cells) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "pic");
        x.putString("key", key);
        x.putIntArray("cells", cells);
        blocks.add(x);
        return this;
    }

    /** An outline on the ground where a building would go (null takes it away). */
    public LifePage ghost(@Nullable CompoundTag g) {
        if (g == null) {
            g = new CompoundTag();
            g.putBoolean("off", true);
        }
        t.put("ghost", g);
        return this;
    }

    /**
     * [wpc] The town's plan as a map of its real lots (TownPlan): each lot a rectangle in world blocks from the map's middle,
     * coloured by what it is (see {@link LotMap#lot}), the chosen one ringed, clicked to choose; the square and you marked.
     */
    public LotMap lotMap(String label, int heartX, int heartZ) {
        CompoundTag x = new CompoundTag();
        x.putString("t", "lotmap");
        x.putString("l", label);
        x.putInt("hx", heartX);
        x.putInt("hz", heartZ);
        ListTag p = new ListTag();
        x.put("lots", p);
        ListTag m = new ListTag();
        x.put("marks", m);
        blocks.add(x);
        return new LotMap(x, p, m);
    }

    /** [wpc] The building itself, its blocks translucent where it would stand (client/BuildGhost); null takes it away. */
    public LifePage blockGhost(@Nullable CompoundTag g) {
        if (g == null) {
            g = new CompoundTag();
            g.putBoolean("off", true);
        }
        t.put("bghost", g);
        return this;
    }

    /** [wpc] A map of lots. */
    public static final class LotMap {
        final CompoundTag x;
        final ListTag lots, marks;

        LotMap(CompoundTag x, ListTag lots, ListTag marks) {
            this.x = x;
            this.lots = lots;
            this.marks = marks;
        }

        /**
         * A lot: its corners (world x and z), its state (0 free to choose, 2 the town's, 3 fields or the mine, 4 yours,
         * 5 another player's, 6 kept for the town), chosen or not, its tooltip, and its action when clicked (null: none).
         */
        public LotMap lot(int x0, int z0, int x1, int z1, int state, boolean chosen, String label, @Nullable String action, @Nullable CompoundTag args) {
            CompoundTag c = new CompoundTag();
            c.putIntArray("b", new int[]{ x0, z0, x1, z1 });
            c.putInt("s", state);
            c.putBoolean("sel", chosen);
            c.putString("l", label);
            if (action != null) {
                c.putString("a", action);
                c.put("args", args == null ? new CompoundTag() : args);
            }
            lots.add(c);
            return this;
        }

        /** A mark at a world spot: kind 1 the square, 2 you. */
        public LotMap mark(int wx, int wz, int kind, String label) {
            CompoundTag c = new CompoundTag();
            c.putInt("x", wx);
            c.putInt("z", wz);
            c.putInt("k", kind);
            c.putString("l", label);
            marks.add(c);
            return this;
        }
    }

    public LifePage message(String text, boolean ok) {
        if (text == null || text.isEmpty()) return this;
        t.putString("msg", text);
        t.putBoolean("ok", ok);
        return this;
    }

    public CompoundTag done() {
        t.put("tabs", tabs);
        t.put("blocks", blocks);
        return t;
    }

    public static final class Row {
        final CompoundTag x;
        final ListTag b;

        Row(CompoundTag x, ListTag b) {
            this.x = x;
            this.b = b;
        }

        /** A button: its label, its action and arguments; rested (with the reason as its tooltip) when not enabled. */
        public Row button(String label, String action, @Nullable CompoundTag args, boolean enabled, String why) {
            CompoundTag c = new CompoundTag();
            c.putString("l", label);
            c.putString("a", action);
            c.put("args", args == null ? new CompoundTag() : args);
            c.putBoolean("on", enabled);
            c.putString("why", why == null ? "" : why);
            b.add(c);
            return this;
        }

        public Row button(String label, String action, @Nullable CompoundTag args) {
            return button(label, action, args, true, "");
        }

        /** The one thing the page is for: drawn with the accent. */
        public Row primary() {
            if (!b.isEmpty()) ((CompoundTag) b.get(b.size() - 1)).putBoolean("main", true);
            return this;
        }

        /** Shift-clicked, the last button's "d" argument counts ten times. */
        public Row shift() {
            if (!b.isEmpty()) ((CompoundTag) b.get(b.size() - 1)).putBoolean("shift", true);
            return this;
        }
    }

    public static final class Map {
        final CompoundTag x;
        final ListTag p;

        Map(CompoundTag x, ListTag p) {
            this.x = x;
            this.p = p;
        }

        /** A place at (dx, dz) blocks from the map's middle; kind 0 a choice, 1 the town's heart, 2 you. */
        public Map place(int dx, int dz, String label, boolean chosen, int kind, @Nullable String action, @Nullable CompoundTag args) {
            CompoundTag c = new CompoundTag();
            c.putInt("x", dx);
            c.putInt("z", dz);
            c.putString("l", label);
            c.putBoolean("sel", chosen);
            c.putInt("k", kind);
            if (action != null) {
                c.putString("a", action);
                c.put("args", args == null ? new CompoundTag() : args);
            }
            p.add(c);
            return this;
        }
    }

    /** Arguments for a button, as pairs: key, value, key, value... (ints, longs and strings). */
    public static CompoundTag args(Object... kv) {
        CompoundTag t = new CompoundTag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v instanceof Integer n) t.putInt(k, n);
            else if (v instanceof Long n) t.putLong(k, n);
            else if (v instanceof Boolean n) t.putBoolean(k, n);
            else t.putString(k, String.valueOf(v));
        }
        return t;
    }
}
