package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [nether] The Nether page of the town's books (CityScreen), as the server's NetherRunners.report has it. On the left a
 * chart of the Nether round the outpost, north up: the fortress, the bastion, the blaze spawners, the quartz and
 * glowstone dug, the lava seas, where a runner was lost, and the runners through the gateway now. On the right, scrolled
 * as one: the runners (each one's card, the leader marked), the run under way or the last plan with its reckoning, what
 * the runs have brought home all told, what the brewer and the enchanter have of it now, the wart farm, the outpost, the
 * highway's plan, the finds, the lost, and the hauls run by run.
 */
public final class NetherPage {

    private NetherPage() {}

    private static final int OUTPOST = 0xFFE9E9E9, FORTRESS = 0xFF5A1E22, BASTION = 0xFF2C2A33, SPAWNER = 0xFFF2A51A, QUARTZ = 0xFFEDE6DA,
        GLOW = 0xFFF5D76E, LAVA = 0xFFE0571B, LOST = 0xFFB0B0FF, FOLK = 0xFF23803A, GOLD = 0xFFD4A017, OTHER = 0xFF9A7A6A;

    private record Row(String text, int colour, int mark, boolean heading) {}

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> finds = compounds(m, "finds"), folk = compounds(m, "runners");
        if (finds.isEmpty() && folk.isEmpty() && m.getList("hauls", Tag.TAG_STRING).isEmpty()) {
            Ui.section(g, font, "The Nether", x, y, w);
            small(g, font, "Nobody goes through the gateway for the town yet.", x + 4, y + 14, Ui.MUTED);
            int cy = y + 24;
            for (String line : wrap(font, "A Nether Age town of " + m.getInt("from") + " folk or more, its gateway built and lit, picks its Nether runners"
                + " from its veterans (one, two at sixty folk, three at a hundred): in the town's armour with a piece of gold on for the piglins,"
                + " a bow, and the brewer's fire resistance, they go through the portal, wall it in on the far side, and bring home quartz,"
                + " glowstone, the fortress's nether wart, the blazes' rods, and what the piglins barter for gold.", w - 8)) {
                small(g, font, line, x + 4, cy, Ui.FAINT);
                cy += 9;
            }
            small(g, font, "The town is in " + m.getString("age") + (m.getBoolean("gateway") ? (m.getBoolean("lit") ? ", its gateway lit." : ", its gateway dark.")
                : ", with no gateway yet."), x + 4, cy + 2, Ui.FAINT);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = map(g, font, finds, x, y, side, mx, my, !m.getString("run").isEmpty());
        int lx = x + side + 8, lw = w - side - 8;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("The Nether runners: " + folk.size() + " of " + m.getInt("wanted") + " wanted", 0, 0, true));
        String run = m.getString("run");
        if (!run.isEmpty()) for (String line : wrap(font, "Under way: " + run, lw - 4)) rows.add(new Row(line, Ui.GOOD, 0, false));
        for (CompoundTag f : folk) {
            String head = f.getString("name") + " (level " + f.getInt("level") + (f.getBoolean("leader") ? ", leads" : "") + ")"
                + (f.getBoolean("through") ? ", through the gateway" : "");
            for (String line : wrap(font, head + ": " + f.getString("card"), lw - 4)) rows.add(new Row(line, f.getBoolean("through") ? Ui.GOOD : Ui.INK, 0, false));
        }
        String plan = m.getString("plan");
        if (!plan.isEmpty()) {
            rows.add(new Row(run.isEmpty() ? "The last plan" : "The plan", 0, 0, true));
            for (String line : wrap(font, plan, lw - 4)) rows.add(new Row(line, Ui.INK, 0, false));
            ListTag reck = m.getList("reckoning", Tag.TAG_STRING);
            for (int i = 0; i < reck.size(); i++) for (String line : wrap(font, "  " + reck.getString(i), lw - 4)) rows.add(new Row(line, Ui.FAINT, 0, false));
        }
        // All told, and what the brewer and the enchanter have of it now.
        rows.add(new Row("Brought home, all told", 0, 0, true));
        List<String> told = new ArrayList<>();
        for (CompoundTag t : compounds(m, "tally")) if (t.getInt("n") > 0) told.add(t.getInt("n") + " " + t.getString("what"));
        for (String line : wrap(font, told.isEmpty() ? "nothing yet" : String.join(", ", told), lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        rows.add(new Row("In the stores now", 0, 0, true));
        List<String> stock = new ArrayList<>();
        for (CompoundTag t : compounds(m, "stock")) stock.add(t.getInt("n") + " " + t.getString("what").toLowerCase(Locale.ROOT));
        for (String line : wrap(font, String.join(", ", stock), lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        rows.add(new Row("The wart farm: " + m.getInt("wartPlants") + (m.getInt("wartPlants") == 1 ? " plant" : " plants") + " growing", Ui.MUTED, 0, false));
        String outpost = m.getString("outpost");
        rows.add(new Row("The outpost", 0, 0, true));
        for (String line : wrap(font, outpost.isEmpty() ? "Not built yet: the first run walls the portal in." : capital(outpost), lw - 4)) {
            rows.add(new Row(line, Ui.INK, OUTPOST, false));
        }
        String high = m.getString("highway");
        if (!high.isEmpty()) for (String line : wrap(font, high, lw - 4)) rows.add(new Row(line, Ui.FAINT, 0, false));
        ListTag lost = m.getList("lost", Tag.TAG_STRING);
        if (!lost.isEmpty()) {
            rows.add(new Row("Lost in the Nether, and waited for", 0, 0, true));
            for (int i = 0; i < lost.size(); i++) rows.add(new Row(lost.getString(i), Ui.WARN, LOST, false));
        }
        if (!finds.isEmpty()) {
            rows.add(new Row("What they found", 0, 0, true));
            List<CompoundTag> big = new ArrayList<>(), small = new ArrayList<>();
            for (CompoundTag t : finds) {
                String k = t.getString("kind");
                if (k.equals("QUARTZ") || k.equals("GLOWSTONE") || k.equals("SOUL") || k.equals("WART") || k.equals("GOLD")) small.add(t);
                else big.add(t);
            }
            big.addAll(small);
            for (CompoundTag t : big) for (String line : wrap(font, line(t), lw - 12)) rows.add(new Row(line, Ui.INK, colour(t), false));
        }
        ListTag hauls = m.getList("hauls", Tag.TAG_STRING);
        if (!hauls.isEmpty()) {
            rows.add(new Row("The runs, the latest first", 0, 0, true));
            for (int i = 0; i < hauls.size(); i++) for (String line : wrap(font, hauls.getString(i), lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        }
        int start = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - 6)));
        int cy = y;
        for (int i = start; i < rows.size(); i++) {
            Row r = rows.get(i);
            int tall = r.heading() ? 11 : 9;
            if (cy + tall > y + h - (i < rows.size() - 1 ? 9 : 0)) {
                small(g, font, "scroll for more (" + (rows.size() - i) + " lines)", lx + 2, y + h - 8, Ui.FAINT);
                break;
            }
            if (r.heading()) {
                Ui.section(g, font, r.text(), lx, cy, lw);
            } else {
                int tx = lx + 2;
                if (r.mark() != 0) {
                    g.fill(lx + 2, cy + 1, lx + 6, cy + 5, r.mark());
                    tx = lx + 9;
                }
                small(g, font, Ui.clip(font, r.text(), (int) ((lx + lw - tx) / 0.75F)), tx, cy, r.colour());
            }
            cy += tall;
        }
        return tip;
    }

    /** One find in a line: "A fortress, 140 blocks from the outpost (the path 60 out) at 210 70 -88". */
    private static String line(CompoundTag t) {
        String kind = t.getString("kind");
        String what = switch (kind) {
            case "FORTRESS" -> capital(t.getString("label")) + ", " + t.getInt("a") + " blocks from the outpost" + (t.getInt("b") > 0 ? " (the path " + t.getInt("b") + " out)" : "");
            case "BASTION" -> capital(t.getString("label")) + ", " + t.getInt("a") + " blocks off (kept clear of)";
            case "QUARTZ", "GLOWSTONE", "GOLD", "SOUL", "WART", "DEBRIS" -> capital(t.getString("label")) + ": " + t.getInt("a") + " dug, " + t.getInt("b") + " brought";
            case "OUTPOST" -> capital(t.getString("label")) + " (" + t.getInt("a") + " blocks of cobblestone)";
            case "LAVA" -> capital(t.getString("label"));
            default -> capital(t.getString("label"));
        };
        return what + " at " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z") + " (day " + (t.getLong("day") + 1) + ", " + t.getString("by") + ")";
    }

    private static int colour(CompoundTag t) {
        return switch (t.getString("kind")) {
            case "OUTPOST" -> OUTPOST;
            case "FORTRESS" -> FORTRESS;
            case "BASTION" -> BASTION;
            case "SPAWNER" -> SPAWNER;
            case "QUARTZ" -> QUARTZ;
            case "GLOWSTONE" -> GLOW;
            case "LAVA" -> LAVA;
            case "LOST" -> LOST;
            case "GOLD", "DEBRIS" -> GOLD;
            default -> OTHER;
        };
    }

    /** The chart: north up, the outpost in the middle, every find where it lies. */
    @Nullable
    private static List<Component> map(GuiGraphics g, Font font, List<CompoundTag> finds, int x, int y, int side, int mx, int my, boolean out) {
        g.fill(x, y, x + side, y + side, 0xFF3A1414);                   // the Nether's red dark
        g.renderOutline(x, y, side, side, Ui.EDGE);
        double far = 48;
        for (CompoundTag t : finds) far = Math.max(far, Math.hypot(t.getInt("dx"), t.getInt("dz")));
        double scale = (side / 2.0 - 10) / (far * 1.05);
        int cx = x + side / 2, cy = y + side / 2;
        for (int ring = 32; ring <= far * 1.05; ring += 32) {
            int rr = (int) (ring * scale);
            for (int a = 0; a < 72; a++) {
                int px = cx + (int) Math.round(Math.cos(Math.toRadians(a * 5)) * rr), py = cy + (int) Math.round(Math.sin(Math.toRadians(a * 5)) * rr);
                if (px > x && px < x + side - 1 && py > y && py < y + side - 1) g.fill(px, py, px + 1, py + 1, 0xFF6A3A30);
            }
            small(g, font, ring + "", cx + rr + 1, cy + 1, 0xFFB08878);
        }
        small(g, font, "N", cx - 2, y + 2, 0xFFE0C8B8);
        List<Component> tip = null;
        for (CompoundTag t : finds) {
            int px = cx + (int) (t.getInt("dx") * scale), py = cy + (int) (t.getInt("dz") * scale);
            String k = t.getString("kind");
            int r = k.equals("FORTRESS") || k.equals("BASTION") ? 3 : k.equals("SPAWNER") || k.equals("LOST") ? 2 : 1;
            g.fill(px - r, py - r, px + r + 1, py + r + 1, colour(t));
            if (k.equals("FORTRESS") || k.equals("BASTION")) g.renderOutline(px - r, py - r, 2 * r + 1, 2 * r + 1, 0xFFE0C8B8);
            if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) tip = List.of(Component.literal(line(t)));
        }
        g.fill(cx - 3, cy - 3, cx + 4, cy + 4, out ? FOLK : OUTPOST);
        g.renderOutline(cx - 3, cy - 3, 7, 7, Ui.EDGE);
        small(g, font, "■ outpost ■ fortress ■ spawner ■ quartz ■ glowstone ■ lava", x + 3, y + side - 9, 0xFFE0C8B8);
        return tip;
    }

    private static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        int max = (int) (width / 0.75F);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.width(next) > max && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder("  " + word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
