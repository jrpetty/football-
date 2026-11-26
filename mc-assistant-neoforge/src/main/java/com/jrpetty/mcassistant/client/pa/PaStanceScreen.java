package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import com.jrpetty.mcassistant.pa.PaStance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * [featpa] Combat stance (Orders > Combat stance): four cards (its own way, Shield, Archer, Medic), each with what it does
 * and whether it has the kit for it (a shield; a bow and arrows; golden apples, potions, food); who it guards (you, or
 * one of the villagers near by); and whether it joins a town's guards when the town is raided.
 */
public final class PaStanceScreen extends PaLogiBase {

    private int poll;

    PaStanceScreen() {
        super("Combat stance");
    }

    @Override
    protected void init() {
        size(400, 250);
    }

    @Override
    protected String title() {
        return "Combat stance";
    }

    @Override
    public void tick() {
        super.tick();
        if (++poll % 40 == 0) PaKitClient.send("st_open");
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag s = PaKitClient.stance;
        if (s == null) { g.drawString(font, "Asking...", left + 10, top + 30, muted(), false); return; }
        int x = left + 8, y = top + 28, iw = w - 16;
        int cw = (iw - 6) / 2, ch = 52;
        int k = s.getInt("k");
        PaStance.Kind[] kinds = PaStance.Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            PaStance.Kind kind = kinds[i];
            int cx = x + (i % 2) * (cw + 6), cy = y + (i / 2) * (ch + 4);
            boolean on = k == kind.ordinal(), over = in(mx, my, cx, cy, cw, ch);
            g.fill(cx, cy, cx + cw, cy + ch, on ? PaScreen.BRAND : over ? 0xFFE6F2EF : 0xFFDCE6E4);
            g.fill(cx, cy + ch - 1, cx + cw, cy + ch, on ? PaScreen.BRAND_LIGHT : 0xFF9DB5B0);
            String icon = switch (kind) { case SHIELD -> "minecraft:shield"; case ARCHER -> "minecraft:bow"; case MEDIC -> "minecraft:golden_apple"; default -> "minecraft:iron_sword"; };
            icon(g, icon, cx + 4, cy + 4, 1);
            g.drawString(font, (on ? "✔ " : "") + kind.label, cx + 24, cy + 8, on ? 0xFFFFFFFF : UiStyle.TEXT, false);
            int ty = cy + 22;
            for (var line : font.split(Component.literal(kind.blurb), cw - 8)) {
                if (ty > cy + ch - 18) break;
                g.drawString(font, line, cx + 4, ty, on ? 0xFFE0F2EE : muted(), false);
                ty += 9;
            }
            String kit = switch (kind) {
                case SHIELD -> s.getBoolean("shield") ? "Has a shield" : "No shield: hand it one";
                case ARCHER -> !s.getBoolean("bow") ? "No bow: hand it one" : s.getInt("arrows") + " arrows";
                case MEDIC -> s.getInt("remedies") == 0 ? "Nothing to heal with" : s.getInt("apples") + " golden apples, " + s.getInt("potions") + " potions, food";
                default -> "Blade close, bow at range";
            };
            boolean ok = switch (kind) { case SHIELD -> s.getBoolean("shield"); case ARCHER -> s.getBoolean("bow") && s.getInt("arrows") > 0; case MEDIC -> s.getInt("remedies") > 0; default -> true; };
            g.drawString(font, UiStyle.clip(font, kit, cw - 8), cx + 4, cy + ch - 11, on ? 0xFFFFFFFF : ok ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            zone(cx, cy, cw, ch, () -> { CompoundTag t = new CompoundTag(); t.putInt("k", kind.ordinal()); PaKitClient.send("st_set", t); }, kind.label + ": " + kind.blurb);
        }
        y += 2 * (ch + 4) + 4;
        // who it guards
        g.drawString(font, "Guards: " + s.getString("ward"), x, y, UiStyle.TEXT, false);
        y += 11;
        int bx = x;
        int bw0 = bw("You");
        button(g, "You", bx, y, bw0, 13, mx, my, true, s.getString("ward").equals("you"), "It guards you", () -> ward(-1));
        bx += bw0 + 4;
        ListTag vs = s.getList("villagers", Tag.TAG_COMPOUND);
        for (int i = 0; i < vs.size(); i++) {
            CompoundTag v = vs.getCompound(i);
            String n = v.getString("n");
            int b = Math.min(90, bw(n));
            if (bx + b > x + iw) break;
            int id = v.getInt("id");
            button(g, n, bx, y, b, 13, mx, my, true, s.getString("ward").equals(n), n + ", " + v.getInt("d") + " blocks away: it guards them (the Shield stance stands by them)", () -> ward(id));
            bx += b + 4;
        }
        y += 18;
        boolean town = s.getBoolean("town");
        String tl = (town ? "✔ " : "") + "Help a town's guards when it's raided";
        button(g, tl, x, y, bw(tl) + 4, 14, mx, my, true, town, "A raid or an attack on a town near by: it goes to the square and fights with the town's guards in its stance, till the all-clear",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", !town); PaKitClient.send("st_town", t); });
        y += 18;
        String def = s.getString("defending");
        if (!def.isEmpty()) g.drawString(font, "Defending " + def + " now.", x, y, UiStyle.DANGER, false);
    }

    private void ward(int id) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        PaKitClient.send("st_ward", t);
    }
}
