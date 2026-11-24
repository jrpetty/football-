package com.jrpetty.mcassistant.pa;

/**
 * [pa36] One chest, gathering or logistics order, read out of a sentence (PaLogiParse) and carried by a PaIntent of
 * kind LOGI as a short spec string, so it rides through the plan, the save and the undo list unchanged. Pure data.
 *
 * <p>op what is done; item the thing ("stone", "oak log", "cooked food", "*" for everything, "it" for the thing just
 * spoken of); qty how much (PaQty); from and to the places (Ref); src how a fill gets its items; arg anything else
 * (a chest's new name, a haul's trip count).
 */
public record PaLogiOrder(Op op, String item, PaQty.Qty qty, Ref from, Ref to, Src src, String arg) {

    /** What the order does. */
    public enum Op {
        /** Its pack into a container. */
        DEPOSIT,
        /** Gather, craft or smelt until a container holds the amount (or is full), with as many trips as it takes. */
        FILL,
        /** Fetch from its player's known chests (or one named) and bring it to its player. */
        FETCH,
        /** Take out of a container into its own pack. */
        TAKE,
        /** Its player's own items into a container, handed over with a confirm in the panel. */
        HANDOVER,
        /** Everything in its pack into storage. */
        EMPTY_PACK,
        /** Everything (or some) from one container to another. */
        MOVE,
        /** Sort a container (or all its player's known ones) by kind. */
        SORT,
        /** A standing job: keep a container stocked with an amount of something. */
        STOCK,
        /** Stop a standing job. */
        UNSTOCK,
        /** Fuel furnaces. */
        FUEL,
        /** Smelt: its ores, or everything in a chest. */
        SMELT,
        /** Take a furnace's output. */
        COLLECT,
        /** Fill a composter. */
        COMPOST,
        /** Give a container a name. */
        NAME,
        /** Make a container the drop-off (where it puts things when its pack is full). */
        DROPOFF,
        /** Turn the automatic drop-off on or off ("when your pack is full, go to the drop-off and come back"). */
        AUTODROP,
        /** A repeating run from one place to another until told to stop. */
        HAUL,
        /** Find the chest with something in it, and ping it. */
        FIND,
        /** Count something across its player's known chests and its pack. */
        COUNT,
        /** Forget a container (it won't use it again). */
        FORGET,
        /** Show the storage map. */
        LIST,
        /** Fetch its player's things from where they died. */
        DEATH,
        /** "How do I make a piston?": the recipe, with what it has. */
        RECIPE,
        /** "What can you make with this?" */
        CANMAKE
    }

    /** Where a fill's items come from. */
    public enum Src { ANY, GATHER, CRAFT, SMELT, PACK, PLAYER, KNOWN }

    /** A place an order names. */
    public record Ref(Kind kind, String name, String type) {

        public enum Kind {
            /** Nothing said. */
            NONE,
            /** "this chest", "that barrel", "there", "in here": what its player looks at or pinged. */
            POINT,
            /** The second thing pointed at ("from this chest to that chest"). */
            POINT2,
            /** "the chest", "the furnace": the one pointed at if it is one, else the nearest known. */
            DEF,
            /** A named one: "the iron chest", "my wood barrel". */
            NAMED,
            /** "the chest by the furnace": the known one nearest the landmark. */
            NEAR,
            /** "my storage", "the stash": its player's storage. */
            STORAGE,
            /** The drop-off. */
            DROPOFF,
            /** All its player's known containers ("my chests"). */
            KNOWN,
            /** The nearest one it may use ("a chest", "the nearest chest"). */
            NEAREST,
            /** Home, base, the house. */
            HOME,
            /** A saved place ("the mine"). */
            WAYPOINT,
            /** Its player. */
            ME,
            /** Its player's ender chest. */
            ENDER,
            /** The place spoken of before ("put it in there too"). */
            IT,
            /** Its own pack. */
            PACK
        }

        public static final Ref NONE = new Ref(Kind.NONE, "", "");

        public static Ref of(Kind k) { return new Ref(k, "", ""); }

        public Ref withType(String t) { return new Ref(kind, name, t == null ? "" : t); }

        public boolean pointed() { return kind == Kind.POINT || kind == Kind.POINT2 || kind == Kind.DEF; }

        public String brief() {
            return kind.name() + (name.isEmpty() ? "" : "(" + name + ")") + (type.isEmpty() || type.equals("chest") ? "" : "[" + type + "]");
        }

        String spec() {
            return kind.name() + "~" + name.replace("|", " ").replace("~", " ") + "~" + type;
        }

        static Ref parse(String s) {
            try {
                String[] p = s.split("~", -1);
                return new Ref(Kind.valueOf(p[0]), p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "");
            } catch (Exception e) {
                return NONE;
            }
        }
    }

    public PaLogiOrder {
        item = item == null ? "" : item;
        qty = qty == null ? PaQty.Qty.NONE : qty;
        from = from == null ? Ref.NONE : from;
        to = to == null ? Ref.NONE : to;
        src = src == null ? Src.ANY : src;
        arg = arg == null ? "" : arg;
    }

    public static PaLogiOrder of(Op op) {
        return new PaLogiOrder(op, "", PaQty.Qty.NONE, Ref.NONE, Ref.NONE, Src.ANY, "");
    }

    public PaLogiOrder withItem(String i) { return new PaLogiOrder(op, i, qty, from, to, src, arg); }
    public PaLogiOrder withQty(PaQty.Qty q) { return new PaLogiOrder(op, item, q, from, to, src, arg); }
    public PaLogiOrder withFrom(Ref r) { return new PaLogiOrder(op, item, qty, r, to, src, arg); }
    public PaLogiOrder withTo(Ref r) { return new PaLogiOrder(op, item, qty, from, r, src, arg); }
    public PaLogiOrder withSrc(Src s) { return new PaLogiOrder(op, item, qty, from, to, s, arg); }
    public PaLogiOrder withArg(String a) { return new PaLogiOrder(op, item, qty, from, to, src, a); }

    /** "FILL stone 100 > POINT" style, for the tests. */
    public String brief() {
        StringBuilder b = new StringBuilder(op.name());
        if (!item.isEmpty()) b.append(' ').append(item);
        if (qty.said()) b.append(' ').append(qty.brief());
        if (from.kind() != Ref.Kind.NONE) b.append(" < ").append(from.brief());
        if (to.kind() != Ref.Kind.NONE) b.append(" > ").append(to.brief());
        if (src != Src.ANY) b.append(" by ").append(src.name());
        if (!arg.isEmpty()) b.append(" [").append(arg).append(']');
        return b.toString();
    }

    /** The order as one line (the PaIntent's item; saved with the plan). */
    public String spec() {
        return String.join("|", op.name(), item.replace("|", " "), qty.brief(), from.spec(), to.spec(), src.name(), arg.replace("|", " "));
    }

    public static PaLogiOrder parse(String spec) {
        try {
            String[] p = spec.split("\\|", -1);
            return new PaLogiOrder(Op.valueOf(p[0]), p[1], PaQty.Qty.parseBrief(p[2]), Ref.parse(p[3]), Ref.parse(p[4]),
                Src.valueOf(p[5]), p.length > 6 ? p[6] : "");
        } catch (Exception e) {
            return null;
        }
    }
}
