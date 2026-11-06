package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The town's quarters, drawn, for the town's books (CityScreen): the Buildings page's map of every lot
 * of the plan tinted by its quarter (the market round the square, the crafts on their side, the homes
 * on theirs, the farmland), every building on it in its quarter's colour, the homes in the crafts'
 * smoke ringed in soot and the homes by the park in green, the mines' heads, and a key with how many
 * buildings each quarter has; and the Why page's lines on the smoke and the park.
 */
final class DistrictMap {

    private DistrictMap() {}

    /** Each quarter's colour, the same on the map, its key and the Buildings page's column. */
    static int colour(String district) {
        return switch (district) {
            case "Square" -> 0xFF7A7A7A;
            case "Market" -> 0xFFB7791F;
            case "Crafts" -> 0xFF8C3B26;
            case "Homes" -> 0xFF2E6FBF;
            case "Fields" -> 0xFF4F8A2E;
            default -> 0xFF7D3C98;
        };
    }

    /** A colour washed out toward the panel, for the ground under the lots. */
    private static int pale(int c, float keep) {
        int r = (c >> 16) & 0xFF, gr = (c >> 8) & 0xFF, b = c & 0xFF;
        int pr = 0xC6, pg = 0xC6, pb = 0xC6;
        r = Math.round(pr + (r - pr) * keep);
        gr = Math.round(pg + (gr - pg) * keep);
        b = Math.round(pb + (b - pb) * keep);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    private static final String[] KEY = { "Square", "Market", "Crafts", "Homes", "Fields", "Outskirts" };

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static void small(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    /**
     * The map, in a box this big: returns the lines for the building under the mouse (its tooltip), or null.
     * North is up, as on the atlas.
     */
    @Nullable
    static List<Component> draw(GuiGraphics g, Font font, CompoundTag data, List<CompoundTag> buildings,
                                int x, int y, int w, int h, int mx, int my) {
        CompoundTag d = data.getCompound("districts");
        int farm = d.contains("farm_side") ? d.getInt("farm_side") : -1;
        int craft = d.contains("craft_side") ? d.getInt("craft_side") : -1;
        int keyW = Math.min(96, Math.max(70, w / 4));
        int size = Math.max(60, Math.min(w - keyW - 8, h - 4));
        // How far out to draw: the furthest building and a lot more, at least the first block.
        int reach = Districts.ROUND_THE_SQUARE + TownPlan.LOT + 4;
        for (CompoundTag b : buildings) {
            reach = Math.max(reach, Math.max(Math.abs(b.getInt("dx")) + b.getInt("hx"), Math.abs(b.getInt("dz")) + b.getInt("hz")) + 4);
        }
        reach = Math.min(reach, TownPlan.reach() + 12);
        float k = size / (2.0F * reach + 1.0F);
        int ox = x, oy = y;
        int cx = ox + size / 2, cy = oy + size / 2;
        g.fill(ox, oy, ox + size, oy + size, 0xFFB4BDA7);                       // the ground
        // Every lot of the plan, washed in its quarter's colour.
        for (TownPlan.Lot lot : TownPlan.lots()) {
            if (lot.kind() != TownPlan.Kind.LOT) continue;
            if (Math.max(Math.abs(lot.x()), Math.abs(lot.z())) - TownPlan.LOT / 2 > reach) continue;
            Districts.District dist = Districts.of(lot, farm, craft);
            int half = TownPlan.LOT / 2;
            box(g, cx, cy, k, size, ox, oy, lot.x() - half, lot.z() - half, lot.x() + half, lot.z() + half,
                pale(colour(dist.label), dist == Districts.District.FIELDS ? 0.45F : 0.28F));
        }
        // The square.
        box(g, cx, cy, k, size, ox, oy, -TownPlan.PLAZA + 1, -TownPlan.PLAZA + 1, TownPlan.PLAZA - 1, TownPlan.PLAZA - 1,
            pale(colour("Square"), 0.5F));
        // The buildings.
        List<Component> tip = null;
        for (CompoundTag b : buildings) {
            if (!b.contains("dx")) continue;
            int bx = b.getInt("dx"), bz = b.getInt("dz"), hx = Math.max(1, b.getInt("hx")), hz = Math.max(1, b.getInt("hz"));
            if ("fortify".equals(b.getString("kind"))) continue;              // the wall is the square's edge
            String dist = b.getString("district");
            int c = "park".equals(b.getString("kind")) ? 0xFF3F9A3A : colour(dist);
            int[] r = box(g, cx, cy, k, size, ox, oy, bx - hx, bz - hz, bx + hx, bz + hz, c);
            if (r == null) continue;
            if ("park".equals(b.getString("kind"))) {
                int fx = cx + Math.round(bx * k), fz = cy + Math.round(bz * k);
                g.fill(fx - 1, fz - 1, fx + 2, fz + 2, 0xFF4A90D9);              // the fountain
            }
            if (b.contains("smoke")) g.renderOutline(r[0] - 1, r[1] - 1, r[2] - r[0] + 2, r[3] - r[1] + 2, 0xFF2A2A2A);
            else if (b.getBoolean("parkside")) g.renderOutline(r[0] - 1, r[1] - 1, r[2] - r[0] + 2, r[3] - r[1] + 2, 0xFF3F9A3A);
            if (b.getBoolean("working")) g.fill(r[2] - 2, r[1], r[2], r[1] + 2, 0xFFE8A030);   // at work: its fire
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                tip = new ArrayList<>();
                tip.add(Component.literal(b.getString("title")));
                tip.add(Component.literal("In " + b.getString("quarter") + ", " + b.getInt("dist") + " blocks " + b.getString("dir")));
                if (b.contains("smoke")) tip.add(Component.literal("In " + b.getString("smoke") + ": its folk sleep the worse for it"));
                if (b.getBoolean("parkside")) tip.add(Component.literal("By the park: its folk are the happier for it"));
                if (b.contains("working")) tip.add(Component.literal(b.getBoolean("working") ? "At work these two days" : "Idle lately"));
            }
        }
        // The mines' heads at work.
        ListTag mines = d.getList("mines", Tag.TAG_COMPOUND);
        for (int i = 0; i < mines.size(); i++) {
            CompoundTag m = mines.getCompound(i);
            int px = cx + Math.round(m.getInt("dx") * k), pz = cy + Math.round(m.getInt("dz") * k);
            if (px < ox + 2 || px > ox + size - 3 || pz < oy + 2 || pz > oy + size - 3) continue;
            g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF262626);
            g.fill(px - 1, pz - 1, px + 1, pz + 1, 0xFFE8A030);
        }
        g.renderOutline(ox, oy, size, size, Ui.EDGE_SOFT);
        small(g, font, "N", cx - 2, oy + 2, Ui.INK);
        // The key.
        int kx = ox + size + 8, ky = oy;
        CompoundTag counts = d.getCompound("counts");
        small(g, font, "QUARTERS", kx, ky, Ui.FAINT);
        ky += 10;
        for (String label : KEY) {
            g.fill(kx, ky, kx + 7, ky + 7, colour(label));
            small(g, font, label + (counts.contains(label) ? " " + counts.getInt(label) : ""), kx + 10, ky, Ui.INK);
            ky += 9;
        }
        ky += 3;
        g.renderOutline(kx, ky, 7, 7, 0xFF2A2A2A);
        small(g, font, "in the smoke", kx + 10, ky, Ui.MUTED);
        ky += 9;
        g.renderOutline(kx, ky, 7, 7, 0xFF3F9A3A);
        small(g, font, "by the park", kx + 10, ky, Ui.MUTED);
        ky += 9;
        g.fill(kx + 2, ky + 2, kx + 5, ky + 5, 0xFFE8A030);
        small(g, font, "at work", kx + 10, ky, Ui.MUTED);
        ky += 12;
        for (String line : new String[]{ d.getString("plan"), d.getString("park") }) {
            for (FormattedCharSequence s : TextCache.split(font, line, (int) ((w - size - 8) / 0.75F))) {
                if (ky > y + h - 8) break;
                small(g, font, s, kx, ky, Ui.MUTED);
                ky += 8;
            }
            ky += 3;
        }
        return tip;
    }

    /** A box of the town (offsets from the heart, inclusive) on the map, clipped to it; its screen corners, or null. */
    @Nullable
    private static int[] box(GuiGraphics g, int cx, int cy, float k, int size, int ox, int oy, int x0, int z0, int x1, int z1, int c) {
        int sx0 = cx + Math.round(x0 * k), sz0 = cy + Math.round(z0 * k);
        int sx1 = cx + Math.round((x1 + 1) * k), sz1 = cy + Math.round((z1 + 1) * k);
        sx0 = Math.max(ox, sx0);
        sz0 = Math.max(oy, sz0);
        sx1 = Math.min(ox + size, Math.max(sx1, sx0 + 1));
        sz1 = Math.min(oy + size, Math.max(sz1, sz0 + 1));
        if (sx0 >= sx1 || sz0 >= sz1) return null;
        g.fill(sx0, sz0, sx1, sz1, c);
        return new int[]{ sx0, sz0, sx1, sz1 };
    }

    /**
     * The Why page's lines on where folk live: the homes in the crafts' smoke and din, and the homes by
     * the park. Returns how far down it got.
     */
    static int why(GuiGraphics g, Font font, CompoundTag data, int x, int y, int w, int bottom) {
        CompoundTag d = data.getCompound("districts");
        ListTag smoky = d.getList("smoky", Tag.TAG_STRING), park = d.getList("parkside", Tag.TAG_STRING);
        Ui.section(g, font, "Where they live: the smoke and the park", x, y, w);
        y += 12;
        if (smoky.isEmpty() && park.isEmpty()) {
            for (FormattedCharSequence s : TextCache.split(font, d.getString("park"), (int) (w / 0.75F))) {
                if (y > bottom - 8) break;
                small(g, font, s, x, y, Ui.MUTED);
                y += 9;
            }
            if (y <= bottom - 8) { small(g, font, "No home is in the crafts' smoke or din.", x, y, Ui.GOOD); y += 9; }
            return y;
        }
        y = list(g, font, "In the smoke and din (folk the less content, the house the cheaper):", smoky, x, y, w, bottom, Ui.BAD);
        y = list(g, font, "By the park (folk the happier, the house the dearer):", park, x, y + 2, w, bottom, Ui.GOOD);
        return y;
    }

    private static int list(GuiGraphics g, Font font, String head, ListTag lines, int x, int y, int w, int bottom, int colour) {
        if (lines.isEmpty() || y > bottom - 16) return y;
        small(g, font, head, x, y, colour);
        y += 9;
        for (int i = 0; i < lines.size(); i++) {
            if (y > bottom - 8) {
                small(g, font, "… and " + (lines.size() - i) + " more", x + 4, y - 1, Ui.FAINT);
                break;
            }
            for (FormattedCharSequence s : TextCache.split(font, "· " + lines.getString(i), (int) ((w - 4) / 0.75F))) {
                if (y > bottom - 8) break;
                small(g, font, s, x + 4, y, Ui.INK);
                y += 9;
            }
        }
        return y;
    }

    /** How many lines the Why page's piece will want, at most (so the drivers above it leave it room). */
    static int whyLines(CompoundTag data) {
        CompoundTag d = data.getCompound("districts");
        int n = d.getList("smoky", Tag.TAG_STRING).size() + d.getList("parkside", Tag.TAG_STRING).size();
        return Math.min(9, Math.max(2, n + (n > 0 ? 2 : 0)));
    }
}
