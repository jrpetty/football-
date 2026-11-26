package com.jrpetty.mcassistant.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [ui53] The town map on the board (the Buildings page's map), drawn to be read at a glance: the land as it lies (the
 * streets, water, fields, trees, sand and rock, read off the world by entity/TownMapReport), every building in the colour
 * of what it is for (homes, shops and businesses, workshops, civic, farm buildings, the stores, the walls and the watch,
 * parks, the mine), and what is happening now: the sites going up with how far along they are, every folk a dot in the
 * colour of what it is at (working, on its way, at leisure, asleep, laid up, at school), the carts, the businesses open,
 * the plots for sale and the houses to let, what is yours ringed in gold, and you, an arrow the way you face.
 *
 * <p>The wheel zooms (on the mouse), a drag pans, the buttons zoom and set it back; north is up, a scale bar says how far.
 * The mouse over anything says what it is (its address, who lives or works there, its owner, how it stands); a click on a
 * folk opens its card, on a business its books. Labels are put where they do not cover each other. The land is one
 * texture, made once for each set of books, so a town of two hundred folk draws as fast as a hamlet.
 */
final class TownMapView {

    private TownMapView() {}

    // ------------------------------------------------------------------ colours, one set for the map and its key

    static final int C_HOME = 0xFF3B7DD8, C_SHOP = 0xFFE8912D, C_WORK = 0xFF9C3A24, C_CIVIC = 0xFF7E4FB0, C_FARM = 0xFF8A6A32,
        C_STORES = 0xFF1E8C82, C_DEFENCE = 0xFF4A5563, C_PARK = 0xFF3FA34D, C_MINE = 0xFF262626, C_OTHER = 0xFF8D8D8D;
    static final int L_UNKNOWN = 0xFFD9D2BE, L_GRASS = 0xFFAFCB8E, L_WATER = 0xFF5B9BD5, L_ROAD = 0xFFE6DDC4, L_FARM = 0xFFD2BF5C,
        L_TREES = 0xFF5C8A44, L_SAND = 0xFFEADFA6, L_ROCK = 0xFF9E9E96, L_SNOW = 0xFFF2F4F7, L_BUILT = 0xFFB59C88, L_LAVA = 0xFFE2541B,
        L_SQUARE = 0xFFCFC5AC;
    private static final int[] LAND = { L_UNKNOWN, L_GRASS, L_WATER, L_ROAD, L_FARM, L_TREES, L_SAND, L_ROCK, L_SNOW, L_BUILT, L_LAVA };
    private static final String[] LAND_WORDS = { "Not seen yet", "Grass", "Water", "Street or path", "Fields", "Trees", "Sand", "Rock",
        "Snow", "Built by others", "Lava" };
    /** Folk, by what they are at (TownMapReport: asleep, working, on the way, at leisure, laid up, at school). */
    static final int[] FOLK = { 0xFF2B3170, 0xFFFFD23F, 0xFFFFFFFF, 0xFFFF7AB6, 0xFFE0362C, 0xFF8FE3FF };
    private static final String[] FOLK_WORDS = { "asleep", "working", "on the way", "at leisure", "laid up", "at school" };
    private static final int SITE = 0xFFF08A24, SALE = 0xFFF2C200, YOURS = 0xFFFFC400, CART = 0xFF6B4423, OPEN = 0xFF2FBF4A,
        SHUT = 0xFF8A8A8A, REPAIR = 0xFFD8342A, INK = 0xFF1C1C1C;

    /** A building's kind of use, its colour and its word in the key. */
    enum Use {
        HOME(C_HOME, "Homes"), SHOP(C_SHOP, "Shops & businesses"), WORK(C_WORK, "Workshops & industry"), CIVIC(C_CIVIC, "Civic & services"),
        FARM(C_FARM, "Farm buildings"), STORES(C_STORES, "Stores"), DEFENCE(C_DEFENCE, "Walls & watch"), PARK(C_PARK, "Parks & sport"),
        MINE(C_MINE, "Mine & pithead");

        final int colour;
        final String words;

        Use(int colour, String words) {
            this.colour = colour;
            this.words = words;
        }
    }

    static Use useOf(String kind, boolean business) {
        String k = kind.toLowerCase(Locale.ROOT);
        if (k.startsWith("house") || k.equals("manor") || k.equals("villa") || k.startsWith("terrace") || k.startsWith("flats")) return Use.HOME;
        switch (k) {
            case "storage", "storehouse", "granary", "store" -> { return Use.STORES; }
            case "mine", "minehead", "pithead" -> { return Use.MINE; }
            case "fortify", "gateway", "clockworkgate", "stockade", "watchtower", "watchhouse", "barracks", "trainingyard", "range", "armoury" -> { return Use.DEFENCE; }
            case "park", "pitch", "arena", "skygarden" -> { return Use.PARK; }
            case "barn", "stable", "apiary", "allotments", "orchard", "windmill" -> { return Use.FARM; }
            case "shop", "market", "bakery", "cafe", "tavern", "inn", "restaurant", "auction", "grandbazaar", "tradingpost", "bank" -> { return Use.SHOP; }
            case "smeltery", "smithy", "workshop", "foundry", "greatforge", "brewery", "enchanter", "fletcher", "wheelwright", "redstoneworks",
                 "machines", "watermill", "divershed", "huntlodge", "lodge", "powderhut", "golemyard" -> { return Use.WORK; }
            default -> { }
        }
        if (k.contains("smith") || k.contains("forge") || k.contains("smelt") || k.contains("kiln") || k.endsWith("works") || k.endsWith("mill")) return Use.WORK;
        return business ? Use.SHOP : Use.CIVIC;
    }

    // ------------------------------------------------------------------ the view, kept while the board is open

    private static float zoom = 1F, panX, panZ, minZoom = 1F;
    /** The map's box as last drawn, its centre, and blocks to pixels: for the wheel and the drag. */
    private static int boxX, boxY, boxW, boxH;
    private static float lastK = 1F;
    private static int lastReach = 64;
    /** A click on something on the map: done when the button comes up, if the mouse did not drag the map meanwhile. */
    @Nullable private static Runnable armed;
    private static double dragged;

    /** The land, as a texture: remade when the books bring other land. */
    private static final ResourceLocation LAND_TEX = ResourceLocation.fromNamespaceAndPath("mc_assistant", "ui53_townmap_land");
    @Nullable private static DynamicTexture landTex;
    @Nullable private static byte[] landOf;
    private static int landN;

    // ------------------------------------------------------------------ input (CityScreen routes it here on the map)

    static boolean mouseScrolled(double mx, double my, double dy) {
        if (!inBox(mx, my) || dy == 0) return false;
        float k0 = lastK, z0 = zoom;
        zoom = clamp((float) (zoom * Math.pow(1.25, Math.signum(dy))), minZoom, 10F);
        if (zoom == z0) return true;
        // The point under the mouse stays under it.
        float cx = boxX + boxW / 2F, cy = boxY + boxH / 2F;
        float wx = panX + (float) (mx - cx) / k0, wz = panZ + (float) (my - cy) / k0;
        float k1 = k0 * zoom / z0;
        panX = wx - (float) (mx - cx) / k1;
        panZ = wz - (float) (my - cy) / k1;
        clampPan();
        return true;
    }

    static boolean mouseDragged(double mx, double my, double dx, double dy) {
        if (!inBox(mx - dx, my - dy) && dragged == 0) return false;
        dragged += Math.abs(dx) + Math.abs(dy);
        panX -= (float) (dx / lastK);
        panZ -= (float) (dy / lastK);
        clampPan();
        return true;
    }

    static boolean mouseReleased() {
        Runnable go = armed;
        boolean was = dragged > 0 || go != null;
        armed = null;
        try {
            if (go != null && dragged < 4) go.run();
        } catch (RuntimeException e) {
            BoardGuard.log("a click on the town map", e);
        }
        dragged = 0;
        return was;
    }

    private static boolean inBox(double mx, double my) {
        return mx >= boxX && mx < boxX + boxW && my >= boxY && my < boxY + boxH;
    }

    private static void clampPan() {
        panX = clamp(panX, -lastReach, lastReach);
        panZ = clamp(panZ, -lastReach, lastReach);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static void reset() {
        zoom = 1F;
        panX = 0;
        panZ = 0;
    }

    // ------------------------------------------------------------------ drawing

    /** What is under the mouse, found while drawing: its words, and what a click on it does (or null). */
    private static final class Hover {
        int rank = -1;
        List<Component> tip;
        Runnable act;

        void offer(int rank, List<Component> tip, @Nullable Runnable act) {
            if (rank <= this.rank) return;
            this.rank = rank;
            this.tip = tip;
            this.act = act;
        }
    }

    /** The map in a box this big, with its key. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    static List<Component> draw(GuiGraphics g, Font font, CompoundTag data, List<CompoundTag> buildings, int x, int y, int w, int h,
                                int mx, int my, EarningsPage.Clicks clicks) {
        CompoundTag m = data.getCompound("townmap");
        int keyW = w >= 430 ? 118 : w >= 320 ? 96 : 0;
        int mw = Math.max(40, w - keyW - (keyW > 0 ? 6 : 0)), mh = Math.max(40, h);
        boxX = x;
        boxY = y;
        boxW = mw;
        boxH = mh;
        int reach = Math.max(24, m.getInt("reach"));
        lastReach = reach;
        // At zoom 1 the town's body fills the box (the buildings but the furthest few); zoomed out, all of its reach.
        int body = bodyOf(buildings, reach);
        minZoom = Math.min(1F, body / (float) reach);
        if (zoom < minZoom) zoom = minZoom;
        float k = Math.min(mw, mh) / (2F * body + 1F) * zoom;
        lastK = k;
        float cx = x + mw / 2F, cy = y + mh / 2F;
        Hover hover = new Hover();
        Counts counts = new Counts();

        g.fill(x, y, x + mw, y + mh, L_UNKNOWN);
        g.enableScissor(x, y, x + mw, y + mh);
        try {
            land(g, m, cx, cy, k, mx, my, hover);
            square(g, data, cx, cy, k);
            mineGround(g, data, cx, cy, k, mx, my, hover);
            Map<Long, CompoundTag> extra = new HashMap<>();
            ListTag bx = m.getList("bx", Tag.TAG_COMPOUND);
            for (int i = 0; i < bx.size(); i++) extra.put(key(bx.getCompound(i).getInt("dx"), bx.getCompound(i).getInt("dz")), bx.getCompound(i));
            List<Label> labels = new ArrayList<>();
            buildingsOn(g, font, buildings, extra, cx, cy, k, mx, my, hover, labels, counts);
            mines(g, data, cx, cy, k, mx, my, hover, labels);
            forSale(g, m, cx, cy, k, mx, my, hover, labels, counts);
            sites(g, m, cx, cy, k, mx, my, hover, labels, counts);
            yours(g, m, cx, cy, k, mx, my, hover, labels);
            stores(g, m, cx, cy, k, mx, my, hover);
            carts(g, m, cx, cy, k, mx, my, hover, counts);
            folk(g, m, cx, cy, k, mx, my, hover, counts);
            me(g, m, cx, cy, k, mx, my, hover);
            placeLabels(g, font, labels, x, y, mw, mh);
        } finally {
            g.disableScissor();
        }
        g.renderOutline(x - 1, y - 1, mw + 2, mh + 2, Ui.EDGE);
        compass(g, font, x + mw - 16, y + 4);
        scaleBar(g, font, x + 5, y + mh - 12, k);
        buttons(g, font, x + 4, y + 4, mx, my, clicks, hover);
        if (!m.contains("land")) small(g, font, "This server sends no land for the map: buildings only.", x + 4, y + mh - 22, INK);
        if (keyW > 0) key(g, font, x + mw + 6, y, keyW, h, counts, m);
        if (hover.act != null && inBox(mx, my)) {
            Runnable act = hover.act;
            clicks.add(mx - 1, my - 1, mx + 2, my + 2, () -> { armed = act; dragged = 0; });
        }
        return inBox(mx, my) || hover.rank >= 100 ? hover.tip : null;
    }

    /** How far out the town's body reaches: the building at the 85th of a hundred by distance, and a margin. */
    private static int bodyOf(List<CompoundTag> buildings, int reach) {
        List<Integer> d = new ArrayList<>();
        for (CompoundTag b : buildings) {
            if (!b.contains("dx") || "fortify".equals(b.getString("kind"))) continue;
            d.add(Math.max(Math.abs(b.getInt("dx")) + b.getInt("hx"), Math.abs(b.getInt("dz")) + b.getInt("hz")));
        }
        if (d.size() < 4) return reach;
        d.sort(null);
        return Math.max(28, Math.min(reach, d.get((int) (d.size() * 0.85F)) + 12));
    }

    private static long key(int dx, int dz) {
        return ((long) dx << 32) ^ (dz & 0xFFFFFFFFL);
    }

    /** Screen x of a world offset from the heart, and y; a box of the town (offsets, inclusive) on the screen. */
    private static int sx(float cx, float k, float dx) { return Math.round(cx + (dx - panX) * k); }
    private static int sy(float cy, float k, float dz) { return Math.round(cy + (dz - panZ) * k); }

    private static int[] rect(float cx, float cy, float k, int x0, int z0, int x1, int z1) {
        int a = sx(cx, k, x0), b = sy(cy, k, z0), c = sx(cx, k, x1 + 1), d = sy(cy, k, z1 + 1);
        if (c <= a) c = a + 1;
        if (d <= b) d = b + 1;
        return new int[]{ a, b, c, d };
    }

    private static boolean over(int[] r, int mx, int my) {
        return mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
    }

    private static void land(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover) {
        byte[] cells = m.getByteArray("land");
        int step = Math.max(1, m.getInt("step")), lr = m.getInt("land_reach");
        int n = (int) Math.round(Math.sqrt(cells.length));
        if (n < 2 || n * n != cells.length) {
            g.fill(boxX, boxY, boxX + boxW, boxY + boxH, L_GRASS);
            return;
        }
        if (landTex == null || landOf != cells || landN != n) makeLand(cells, n);
        if (landTex == null) return;
        float x0 = cx + (-lr - step / 2F - panX) * k, y0 = cy + (-lr - step / 2F - panZ) * k;
        float px = step * k;
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0);
        g.pose().scale(px, px, 1F);
        g.blit(LAND_TEX, 0, 0, n, n, 0F, 0F, n, n, n, n);
        g.pose().popPose();
        // The ground under the mouse, last of all (anything on it says more).
        int i = (int) Math.floor((mx - x0) / px), j = (int) Math.floor((my - y0) / px);
        if (i >= 0 && j >= 0 && i < n && j < n && inBox(mx, my)) {
            int kind = cells[j * n + i];
            if (kind >= 0 && kind < LAND_WORDS.length) hover.offer(0, List.of(Component.literal(LAND_WORDS[kind])), null);
        }
    }

    private static void makeLand(byte[] cells, int n) {
        try {
            if (landTex == null || landN != n) {
                if (landTex != null) Minecraft.getInstance().getTextureManager().release(LAND_TEX);
                landTex = new DynamicTexture(n, n, false);
                Minecraft.getInstance().getTextureManager().register(LAND_TEX, landTex);
            }
            NativeImage img = landTex.getPixels();
            if (img == null) return;
            for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
                int kind = cells[j * n + i];
                int c = kind >= 0 && kind < LAND.length ? LAND[kind] : L_BUILT;
                // A little relief so streets and water read against the grass: every other row of fields striped.
                if (kind == 4 && (i + j) % 2 == 0) c = shade(c, 0.92F);
                img.setPixelRGBA(i, j, abgr(c));
            }
            landTex.upload();
            landOf = cells;
            landN = n;
        } catch (RuntimeException e) {
            BoardGuard.log("the town map's land", e);
            landTex = null;
        }
    }

    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    static int shade(int c, float f) {
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * f)), gr = Math.min(255, Math.round(((c >> 8) & 0xFF) * f)),
            b = Math.min(255, Math.round((c & 0xFF) * f));
        return (c & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    private static void square(GuiGraphics g, CompoundTag data, float cx, float cy, float k) {
        int p = com.jrpetty.mcassistant.village.TownPlan.PLAZA - 1;
        int[] r = rect(cx, cy, k, -p, -p, p, p);
        g.fill(r[0], r[1], r[2], r[3], L_SQUARE);
        g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1], shade(L_SQUARE, 0.75F));
    }

    private static void mineGround(GuiGraphics g, CompoundTag data, float cx, float cy, float k, int mx, int my, Hover hover) {
        ListTag ground = data.getCompound("districts").getList("mineGround", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < ground.size(); i++) {
            int[] q = ground.getIntArray(i);
            if (q.length != 4) continue;
            int[] r = rect(cx, cy, k, q[0], q[1], q[2], q[3]);
            g.fill(r[0], r[1], r[2], r[3], 0x386B5136);
            dashed(g, r[0], r[1], r[2] - r[0], r[3] - r[1], 0xC06B5136);
            if (over(r, mx, my)) hover.offer(5, List.of(Component.literal("The mine's ground"),
                Component.literal("Set aside for the town's mine: nothing else is built on it").withColor(0xBBBBBB)), null);
        }
    }

    /** Diagonal stripes over a box, every {@code gap} pixels. */
    private static void hatch(GuiGraphics g, int[] r, int colour, int gap) {
        int w = r[2] - r[0], h = r[3] - r[1];
        if (w <= 0 || h <= 0) return;
        if (w * h > 2500) {                                                   // big: upright stripes, one box each (cheap)
            for (int i = 0; i < w; i += gap) g.fill(r[0] + i, r[1], r[0] + i + 1, r[3], colour);
            return;
        }
        for (int d = -h; d < w; d += gap) {
            for (int t = 0; t < h; t++) {
                int px = r[0] + d + t;
                if (px >= r[0] && px < r[2]) g.fill(px, r[1] + t, px + 1, r[1] + t + 1, colour);
            }
        }
    }

    private record Label(String text, int ax, int ay, int colour, int rank) {}

    private static void buildingsOn(GuiGraphics g, Font font, List<CompoundTag> buildings, Map<Long, CompoundTag> extra, float cx, float cy,
                                    float k, int mx, int my, Hover hover, List<Label> labels, Counts counts) {
        for (CompoundTag b : buildings) {
            if (!b.contains("dx")) continue;
            int bx = b.getInt("dx"), bz = b.getInt("dz"), hx = Math.max(1, b.getInt("hx")), hz = Math.max(1, b.getInt("hz"));
            CompoundTag e = extra.getOrDefault(key(bx, bz), new CompoundTag());
            String kind = b.getString("kind");
            Use use = useOf(kind, e.contains("biz"));
            counts.uses[use.ordinal()]++;
            if (kind.equals("fortify")) {                                       // the wall: a ring round the town, not a block
                int[] r = rect(cx, cy, k, bx - hx, bz - hz, bx + hx, bz + hz);
                int t = Math.max(1, Math.round(k * 0.8F));
                g.fill(r[0], r[1], r[2], r[1] + t, use.colour);
                g.fill(r[0], r[3] - t, r[2], r[3], use.colour);
                g.fill(r[0], r[1], r[0] + t, r[3], use.colour);
                g.fill(r[2] - t, r[1], r[2], r[3], use.colour);
                boolean onEdge = over(r, mx, my) && !over(new int[]{ r[0] + t + 2, r[1] + t + 2, r[2] - t - 2, r[3] - t - 2 }, mx, my);
                if (onEdge) hover.offer(20, List.of(Component.literal("The town wall"), Component.literal(use.words).withColor(0xBBBBBB)), null);
                continue;
            }
            int[] r = rect(cx, cy, k, bx - hx, bz - hz, bx + hx, bz + hz);
            g.fill(r[0], r[1], r[2], r[3], use.colour);
            g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1], shade(use.colour, 0.55F));
            int bw = r[2] - r[0], bh = r[3] - r[1];
            // How it stands, in the corner: at work, open or shut, wants mending, a storey going up.
            if (b.getBoolean("raising") && bw > 3) hatch(g, r, 0x90F08A24, 3);
            if (e.contains("open") && bw >= 4) {
                int c = e.getBoolean("open") ? OPEN : SHUT;
                g.fill(r[2] - 3, r[1] + 1, r[2] - 1, r[1] + 3, c);
                if (e.getBoolean("open")) counts.open++;
            } else if (b.getBoolean("working") && bw >= 4) {
                g.fill(r[2] - 3, r[1] + 1, r[2] - 1, r[1] + 3, 0xFFFFB030);
            }
            if ("needs repair".equals(b.getString("repair")) && bw >= 4) g.fill(r[0] + 1, r[3] - 3, r[0] + 3, r[3] - 1, REPAIR);
            if (e.contains("vacant") && bw >= 4) {
                dashed(g, r[0] - 1, r[1] - 1, bw + 2, bh + 2, 0xFFFFFFFF);
                counts.toLet++;
            }
            String name = e.contains("biz") ? e.getString("biz") : b.getString("title");
            if (use != Use.HOME && use != Use.DEFENCE) labels.add(new Label(name, (r[0] + r[2]) / 2, r[1], INK,
                (use == Use.CIVIC || use == Use.STORES || use == Use.MINE ? 30 : use == Use.SHOP ? 25 : 15) + Math.min(9, bw * bh / 40)));
            if (over(r, mx, my)) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(name).withColor(0xFFFFFF));
                if (e.contains("biz")) tip.add(Component.literal(e.getString("biz_type") + " in the " + b.getString("title").toLowerCase(Locale.ROOT)
                    + (e.getBoolean("open") ? " · open" : " · shut")).withColor(e.getBoolean("open") ? 0x9EE07A : 0xBBBBBB));
                tip.add(Component.literal(use.words + (e.contains("addr") ? " · " + e.getString("addr") : "")).withColor(use.colour & 0xFFFFFF | 0x404040));
                if (e.contains("biz_owner") && !e.getString("biz_owner").isEmpty()) tip.add(Component.literal("Owned by " + e.getString("biz_owner")));
                if (e.contains("owner")) tip.add(Component.literal("A player's: " + e.getString("owner")));
                if (!e.getString("who").isEmpty()) tip.add(Component.literal("Lives here: " + e.getString("who")));
                else if (b.contains("living")) tip.add(Component.literal(b.getInt("living") + " living here, " + b.getString("tenure")));
                if (e.contains("vacant")) tip.add(Component.literal("Empty: " + e.getString("vacant")).withColor(0xF2E08A));
                if (b.getBoolean("raising")) tip.add(Component.literal("A storey going up").withColor(0xF0A060));
                if (b.contains("repair")) tip.add(Component.literal(capital(b.getString("repair"))).withColor(0xE08070));
                if (b.contains("working")) tip.add(Component.literal(b.getBoolean("working") ? "At work these two days" : "Idle lately").withColor(0xBBBBBB));
                if (b.contains("smoke")) tip.add(Component.literal("In " + b.getString("smoke")).withColor(0xBBBBBB));
                if (b.getInt("furnish_of") > 0) tip.add(Component.literal("Furnished " + Math.round(b.getInt("furnished") * 100f / b.getInt("furnish_of")) + "%").withColor(0xBBBBBB));
                String id = e.getString("biz_id");
                Runnable act = id.isEmpty() ? null : () -> {
                    UiStyle.click();
                    com.jrpetty.mcassistant.net.LifeNetwork.command("village business books " + BusinessScreen.quoted(id));
                };
                if (act != null) tip.add(Component.literal("Click for its books").withColor(0x9AA3B2));
                hover.offer(30 + Math.max(0, 20 - Math.min(20, bw * bh / 30)), tip, act);   // the smaller on top
            }
        }
    }

    private static void mines(GuiGraphics g, CompoundTag data, float cx, float cy, float k, int mx, int my, Hover hover, List<Label> labels) {
        ListTag mines = data.getCompound("districts").getList("mines", Tag.TAG_COMPOUND);
        for (int i = 0; i < mines.size(); i++) {
            CompoundTag mn = mines.getCompound(i);
            int px = sx(cx, k, mn.getInt("dx") + 0.5F), pz = sy(cy, k, mn.getInt("dz") + 0.5F);
            int s = Math.max(3, Math.round(k * 1.5F));
            g.fill(px - s, pz - s, px + s, pz + s, C_MINE);
            g.fill(px - s / 2, pz - s / 2, px + s / 2 + 1, pz + s / 2 + 1, 0xFFE8A030);
            labels.add(new Label("Pithead", px, pz - s, INK, 32));
            if (Math.abs(mx - px) <= s && Math.abs(my - pz) <= s) hover.offer(60, List.of(Component.literal("The pithead"),
                Component.literal("The mine's head: the shaft and the lift down to its landings").withColor(0xBBBBBB)), null);
        }
    }

    private static void forSale(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover, List<Label> labels, Counts counts) {
        ListTag sale = m.getList("sale", Tag.TAG_COMPOUND);
        for (int i = 0; i < sale.size(); i++) {
            CompoundTag p = sale.getCompound(i);
            int dx = p.getInt("dx"), dz = p.getInt("dz"), hx = Math.max(1, p.getInt("hx")), hz = Math.max(1, p.getInt("hz"));
            int[] r = rect(cx, cy, k, dx - hx, dz - hz, dx + hx, dz + hz);
            g.fill(r[0], r[1], r[2], r[3], 0x40F2C200);
            dashed(g, r[0], r[1], r[2] - r[0], r[3] - r[1], SALE);
            dashed(g, r[0] + 1, r[1] + 1, r[2] - r[0] - 2, r[3] - r[1] - 2, SALE);
            counts.sale++;
            String what = p.getString("listing").isEmpty() ? "for sale" : p.getString("listing");
            labels.add(new Label(capital(what), (r[0] + r[2]) / 2, r[1], 0xFF6B5200, 40));
            if (over(r, mx, my)) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(p.getString("what")).withColor(0xFFFFFF));
                tip.add(Component.literal(capital(what) + (p.getInt("asking") > 0 ? " · asking " + p.getInt("asking") + "c" : "")
                    + (p.getInt("rent") > 0 ? " · rent " + p.getInt("rent") + "c" : "")).withColor(0xF2E08A));
                tip.add(Component.literal("The Property page has the office and its listings").withColor(0x9AA3B2));
                hover.offer(70, tip, null);
            }
        }
    }

    private static void sites(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover, List<Label> labels, Counts counts) {
        ListTag sites = m.getList("sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < sites.size(); i++) {
            CompoundTag s = sites.getCompound(i);
            int dx = s.getInt("dx"), dz = s.getInt("dz"), hx = Math.max(1, s.getInt("hx")), hz = Math.max(1, s.getInt("hz"));
            int[] r = rect(cx, cy, k, dx - hx, dz - hz, dx + hx, dz + hz);
            int pct = s.getInt("pct");
            counts.sites++;
            if (s.getBoolean("ring")) {
                dashed(g, r[0], r[1], r[2] - r[0], r[3] - r[1], SITE);
            } else {
                g.fill(r[0], r[1], r[2], r[3], 0x50F08A24);
                hatch(g, r, 0xC0F08A24, 3);
                dashed(g, r[0], r[1], r[2] - r[0], r[3] - r[1], 0xFF8A4A10);
                if (pct >= 0) {                                                   // how far along, under it
                    int bw = Math.max(10, r[2] - r[0]), bx = (r[0] + r[2]) / 2 - bw / 2;
                    g.fill(bx, r[3] + 1, bx + bw, r[3] + 4, 0xFF3A3A3A);
                    g.fill(bx + 1, r[3] + 2, bx + 1 + Math.round((bw - 2) * pct / 100F), r[3] + 3, OPEN);
                }
            }
            String what = s.getString("what");
            labels.add(new Label(what + (pct >= 0 ? " " + pct + "%" : ""), (r[0] + r[2]) / 2, r[1], 0xFF7A3A00, 50));
            if (over(r, mx, my)) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(what + ": going up").withColor(0xFFFFFF));
                tip.add(Component.literal(pct >= 0 ? pct + "% of its walls and floors laid" : "Its ground is not loaded: how far along is not known here")
                    .withColor(0xF0A060));
                hover.offer(80, tip, null);
            }
        }
    }

    private static void yours(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover, List<Label> labels) {
        ListTag mine = m.getList("mine", Tag.TAG_COMPOUND);
        for (int i = 0; i < mine.size(); i++) {
            CompoundTag p = mine.getCompound(i);
            int dx = p.getInt("dx"), dz = p.getInt("dz"), hx = Math.max(1, p.getInt("hx")), hz = Math.max(1, p.getInt("hz"));
            int[] r = rect(cx, cy, k, dx - hx, dz - hz, dx + hx, dz + hz);
            g.renderOutline(r[0] - 2, r[1] - 2, r[2] - r[0] + 4, r[3] - r[1] + 4, YOURS);
            g.renderOutline(r[0] - 1, r[1] - 1, r[2] - r[0] + 2, r[3] - r[1] + 2, YOURS);
            labels.add(new Label("★ Yours", (r[0] + r[2]) / 2, r[1] - 2, 0xFF7A5A00, 90));
            if (over(r, mx, my)) hover.offer(85, List.of(Component.literal("★ " + capital(p.getString("what"))).withColor(0xFFD84A)), null);
        }
    }

    private static void stores(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover) {
        int[] d = m.getIntArray("stores");
        if (d.length != 2) return;
        int px = sx(cx, k, d[0] + 0.5F), pz = sy(cy, k, d[1] + 0.5F);
        g.fill(px - 2, pz - 2, px + 3, pz + 3, INK);
        g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFF9EF0E6);
        if (Math.abs(mx - px) <= 3 && Math.abs(my - pz) <= 3) hover.offer(90, List.of(Component.literal("The storehouse door"),
            Component.literal("Where the couriers and the makers bring the town's goods").withColor(0xBBBBBB)), null);
    }

    private static void carts(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover, Counts counts) {
        ListTag carts = m.getList("carts", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < carts.size(); i++) {
            int[] c = carts.getIntArray(i);
            if (c.length != 2) continue;
            int px = sx(cx, k, c[0] + 0.5F), pz = sy(cy, k, c[1] + 0.5F);
            g.fill(px - 3, pz - 2, px + 3, pz + 2, INK);
            g.fill(px - 2, pz - 1, px + 2, pz + 1, CART);
            counts.carts++;
            if (Math.abs(mx - px) <= 3 && Math.abs(my - pz) <= 3) hover.offer(95, List.of(Component.literal("A cart on the road"),
                Component.literal("A trade wagon, carrying for the town").withColor(0xBBBBBB)), null);
        }
    }

    private static void folk(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover, Counts counts) {
        ListTag folk = m.getList("folk", Tag.TAG_COMPOUND);
        int s = k >= 3 ? 2 : 1;
        int bestD = 26;
        CompoundTag best = null;
        for (int i = 0; i < folk.size(); i++) {
            CompoundTag f = folk.getCompound(i);
            int st = Math.max(0, Math.min(FOLK.length - 1, f.getByte("s")));
            counts.folk[st]++;
            int px = sx(cx, k, f.getInt("x") + 0.5F), pz = sy(cy, k, f.getInt("z") + 0.5F);
            if (px < boxX - 4 || pz < boxY - 4 || px > boxX + boxW + 4 || pz > boxY + boxH + 4) continue;
            g.fill(px - s - 1, pz - s - 1, px + s + 1, pz + s + 1, f.getBoolean("carry") ? 0xFFFFFFFF : INK);
            g.fill(px - s, pz - s, px + s, pz + s, FOLK[st]);
            int d = (mx - px) * (mx - px) + (my - pz) * (my - pz);
            if (d < bestD) { bestD = d; best = f; }
        }
        if (best != null) {
            CompoundTag f = best;
            int st = Math.max(0, Math.min(FOLK.length - 1, f.getByte("s")));
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(f.getString("n") + ", " + f.getString("t").toLowerCase(Locale.ROOT)).withColor(0xFFFFFF));
            tip.add(Component.literal(f.getString("d").isEmpty() ? capital(FOLK_WORDS[st]) : f.getString("d") + " (" + FOLK_WORDS[st] + ")")
                .withColor(st == 0 ? 0x9AA8FF : FOLK[st] & 0xFFFFFF));
            if (f.getBoolean("carry")) tip.add(Component.literal("A hauler: carries the town's goods").withColor(0xBBBBBB));
            Runnable act = null;
            if (f.hasUUID("u")) {
                UUID who = f.getUUID("u");
                act = () -> { UiStyle.click(); FolkProfileScreen.open(who, null, Minecraft.getInstance().screen); };
                tip.add(Component.literal("Click for its card").withColor(0x9AA3B2));
            }
            hover.offer(100, tip, act);
        }
    }

    private static void me(GuiGraphics g, CompoundTag m, float cx, float cy, float k, int mx, int my, Hover hover) {
        if (!m.contains("me")) return;
        CompoundTag me = m.getCompound("me");
        float dx = (float) me.getDouble("dx"), dz = (float) me.getDouble("dz");
        int px = sx(cx, k, dx), pz = sy(cy, k, dz);
        double a = Math.toRadians(me.getFloat("yaw"));
        // An arrow the way you face (yaw 0 faces south, +z, which is down the map).
        float fx = (float) -Math.sin(a), fz = (float) Math.cos(a);
        for (int t = -1; t <= 7; t++) {
            int lx = Math.round(px + fx * t), lz = Math.round(pz + fz * t);
            g.fill(lx - 1, lz - 1, lx + 2, lz + 2, INK);
        }
        for (int t = 0; t <= 6; t++) {
            int lx = Math.round(px + fx * t), lz = Math.round(pz + fz * t);
            g.fill(lx, lz, lx + 1, lz + 1, 0xFFFFFFFF);
        }
        g.fill(px - 3, pz - 3, px + 4, pz + 4, INK);
        g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFFFF4040);
        g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFFFFFFF);
        if (Math.abs(mx - px) <= 4 && Math.abs(my - pz) <= 4) hover.offer(110, List.of(Component.literal("You").withColor(0xFF8080),
            Component.literal("Where you stood when the board opened").withColor(0xBBBBBB)), null);
    }

    /** The labels, the most telling first, each where it covers no other (above its building, or below, or not at all). */
    private static void placeLabels(GuiGraphics g, Font font, List<Label> labels, int x, int y, int w, int h) {
        if (lastK * 1F < 0.9F && labels.size() > 30) labels.removeIf(l -> l.rank() < 30);
        labels.sort((a, b) -> b.rank() - a.rank());
        List<int[]> taken = new ArrayList<>();
        int shown = 0;
        for (Label l : labels) {
            if (shown >= 60) break;
            String text = Ui.clip(font, l.text(), 160);
            int tw = (int) Math.ceil(font.width(text) * 0.75F) + 4, th = 8;
            int[][] tries = { { l.ax() - tw / 2, l.ay() - th - 1 }, { l.ax() - tw / 2, l.ay() + 2 } };
            for (int[] t : tries) {
                int[] r = { t[0], t[1], t[0] + tw, t[1] + th };
                if (r[0] < x + 1 || r[1] < y + 1 || r[2] > x + w - 1 || r[3] > y + h - 1) continue;
                boolean clash = false;
                for (int[] o : taken) if (r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3]) { clash = true; break; }
                if (clash) continue;
                taken.add(r);
                g.fill(r[0], r[1], r[2], r[3], 0xC8F4F1E8);
                small(g, font, text, r[0] + 2, r[1] + 1, l.colour());
                shown++;
                break;
            }
        }
    }

    private static void compass(GuiGraphics g, Font font, int x, int y) {
        g.fill(x, y, x + 12, y + 16, 0xC8F4F1E8);
        g.renderOutline(x, y, 12, 16, Ui.EDGE_SOFT);
        for (int t = 0; t < 5; t++) g.fill(x + 6 - t / 2 - (t > 0 ? 1 : 0), y + 2 + t, x + 6 + t / 2 + 1, y + 3 + t, 0xFFC0392B);
        small(g, font, "N", x + 4, y + 8, INK);
    }

    private static void scaleBar(GuiGraphics g, Font font, int x, int y, float k) {
        int[] nice = { 4, 8, 16, 32, 64, 128 };
        int blocks = 128;
        for (int n : nice) if (n * k >= 30) { blocks = n; break; }
        int len = Math.max(4, Math.round(blocks * k));
        g.fill(x - 2, y - 2, x + len + 34, y + 9, 0xC8F4F1E8);
        g.fill(x, y + 4, x + len, y + 6, INK);
        g.fill(x, y + 1, x + 1, y + 7, INK);
        g.fill(x + len - 1, y + 1, x + len, y + 7, INK);
        small(g, font, blocks + " blocks", x + len + 3, y + 1, INK);
    }

    private static void buttons(GuiGraphics g, Font font, int x, int y, int mx, int my, EarningsPage.Clicks clicks, Hover hover) {
        String[] labels = { "+", "−", "⌂" };
        String[] tips = { "Zoom in (or the wheel)", "Zoom out", "Back to the whole town" };
        for (int i = 0; i < labels.length; i++) {
            int bx = x + i * 13;
            boolean overIt = mx >= bx && mx < bx + 12 && my >= y && my < y + 12;
            UiStyle.tile(g, bx, y, 12, 12, UiStyle.State.of(true, false, overIt));
            g.drawString(font, labels[i], bx + (12 - font.width(labels[i])) / 2, y + 2, INK, false);
            final int which = i;
            clicks.add(bx, y, bx + 12, y + 12, () -> {
                UiStyle.click();
                if (which == 0) zoomAbout(1);
                else if (which == 1) zoomAbout(-1);
                else reset();
            });
            if (overIt) hover.offer(200, List.of(Component.literal(tips[i])), null);
        }
        String hint = "drag to move \u00B7 wheel to zoom";
        g.fill(x + 40, y + 1, x + 44 + (int) Math.ceil(font.width(hint) * 0.75F), y + 11, 0xC8F4F1E8);
        small(g, font, hint, x + 42, y + 3, 0xFF3A3A3A);
    }

    private static void zoomAbout(int dir) {
        zoom = clamp((float) (zoom * Math.pow(1.5, dir)), minZoom, 10F);
        if (zoom <= minZoom) { panX = 0; panZ = 0; }
        clampPan();
    }

    /** What the key counts as it draws. */
    private static final class Counts {
        final int[] uses = new int[Use.values().length];
        final int[] folk = new int[FOLK.length];
        int sites, sale, toLet, open, carts;
    }

    private static void key(GuiGraphics g, Font font, int x, int y, int w, int h, Counts c, CompoundTag m) {
        int ky = y, bottom = y + h;
        int tw = (int) ((w - 12) / 0.75F);
        heading(g, font, "BUILDINGS", x, ky);
        ky += 9;
        for (Use u : Use.values()) {
            if (ky > bottom - 8) return;
            if (c.uses[u.ordinal()] == 0 && (u == Use.MINE || u == Use.PARK || u == Use.DEFENCE)) continue;
            g.fill(x, ky, x + 7, ky + 7, u.colour);
            g.renderOutline(x, ky, 7, 7, shade(u.colour, 0.55F));
            small(g, font, Ui.clip(font, u.words + " " + c.uses[u.ordinal()], tw), x + 10, ky, INK);
            ky += 8;
        }
        ky += 3;
        heading(g, font, "LAND", x, ky);
        ky += 9;
        int[][] land = { { L_ROAD, 3 }, { L_WATER, 2 }, { L_FARM, 4 }, { L_GRASS, 1 }, { L_TREES, 5 }, { L_SQUARE, -1 }, { L_BUILT, 9 } };
        for (int[] l : land) {
            if (ky > bottom - 8) return;
            g.fill(x, ky, x + 7, ky + 7, l[0]);
            g.renderOutline(x, ky, 7, 7, shade(l[0], 0.7F));
            small(g, font, l[1] < 0 ? "The square" : LAND_WORDS[l[1]], x + 10, ky, INK);
            ky += 8;
        }
        ky += 3;
        heading(g, font, "NOW", x, ky);
        ky += 9;
        int all = 0;
        for (int n : c.folk) all += n;
        int[] order = { 1, 2, 3, 0, 4, 5 };
        for (int s : order) {
            if (ky > bottom - 8) return;
            if (c.folk[s] == 0 && (s == 4 || s == 5)) continue;
            g.fill(x + 1, ky + 1, x + 6, ky + 6, INK);
            g.fill(x + 2, ky + 2, x + 5, ky + 5, FOLK[s]);
            small(g, font, Ui.clip(font, c.folk[s] + " " + FOLK_WORDS[s], tw), x + 10, ky, INK);
            ky += 8;
        }
        Object[][] marks = {
            { SITE, c.sites + (c.sites == 1 ? " building going up" : " buildings going up"), "site" },
            { SALE, c.sale + " for sale or to let", "sale" },
            { OPEN, c.open + " businesses open", "dot" },
            { CART, c.carts + (c.carts == 1 ? " cart" : " carts") + " on the roads", "dot" },
            { YOURS, "Yours", "ring" },
            { 0xFFFF4040, "You", "dot" } };
        for (Object[] mk : marks) {
            if (ky > bottom - 8) return;
            int col = (Integer) mk[0];
            String kind = (String) mk[2];
            if (kind.equals("site")) { g.fill(x, ky, x + 7, ky + 7, 0x50F08A24); hatch(g, new int[]{ x, ky, x + 7, ky + 7 }, 0xC0F08A24, 3); }
            else if (kind.equals("sale")) dashed(g, x, ky, 7, 7, col);
            else if (kind.equals("ring")) { g.renderOutline(x, ky, 7, 7, col); g.renderOutline(x + 1, ky + 1, 5, 5, col); }
            else { g.fill(x + 1, ky + 1, x + 6, ky + 6, INK); g.fill(x + 2, ky + 2, x + 5, ky + 5, col); }
            small(g, font, Ui.clip(font, (String) mk[1], tw), x + 10, ky, INK);
            ky += 8;
        }
        if (ky + 16 < bottom) {
            ky += 3;
            for (net.minecraft.util.FormattedCharSequence s : TextCache.split(font, all + " folk on the map. The mouse over anything says what it is; "
                + "a click on a folk opens its card, on a business its books.", (int) ((w - 2) / 0.75F))) {
                if (ky > bottom - 8) break;
                small(g, font, s, x, ky, 0xFF4A4A4A);
                ky += 8;
            }
        }
    }

    private static void heading(GuiGraphics g, Font font, String s, int x, int y) {
        small(g, font, s, x, y, 0xFF555555);
    }

    /** A dashed outline, two on and two off. */
    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int c) {
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < w; i += 4) {
            int e = Math.min(x + w, x + i + 2);
            g.fill(x + i, y, e, y + 1, c);
            g.fill(x + i, y + h - 1, e, y + h, c);
        }
        for (int j = 0; j < h; j += 4) {
            int e = Math.min(y + h, y + j + 2);
            g.fill(x, y + j, x + 1, e, c);
            g.fill(x + w - 1, y + j, x + w, e, c);
        }
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static void small(GuiGraphics g, Font font, net.minecraft.util.FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
