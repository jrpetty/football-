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
 * one of the villagers near by), with "Show me"; and whether it joins a town's guards when the town is raided.
 */
public final class PaStanceScreen extends PaKitBase {

    private int poll;

    PaStanceScreen() {
        super("Combat stance", "st");
    }

    @Override
    protected void init() {
        size(404, 268);
    }

    @Override
    protected String title() {
        return "Combat stance";
    }

    @Override
    protected String idleWords() {
        return "Click a card to set its stance. Its nameplate shows the stance; every click says here what changed.";
    }

    @Override
    public void tick() {
        super.tick();
        if (++poll % 40 == 0) PaKitClient.send("st_open");
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag s = PaKitClient.stance;
        int x = left + 8, y = top + 28, iw = w - 16;
        int footH = 30, foot = top + h - footH - 6;
        if (hint(g, x, y, iw, "Combat stances", new String[]{
                "Shield draws monsters onto itself and blocks; Archer keeps its distance and shoots; Medic stays back and heals.",
                "Each card says if it's missing the kit for that stance: hand it over in its Pack tab.",
                "Pick who it guards: you, or a villager near by.",
                "With Help a town's guards on, it joins a town's defence in a raid, in its stance."}, mx, my)) return;
        feedback(g, x, foot, iw, footH);
        if (s == null) { g.drawString(font, "Asking it...", x, y + 2, UiStyle.TEXT_MUTED, false); return; }
        int cw = (iw - 6) / 2, ch = 52;
        int k = s.getInt("k");
        PaStance.Kind[] kinds = PaStance.Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            PaStance.Kind kind = kinds[i];
            int cx = x + (i % 2) * (cw + 6), cy = y + (i / 2) * (ch + 4);
            boolean on = k == kind.ordinal();
            tile(g, cx, cy, cw, ch, on, mx, my);
            String icon = switch (kind) { case SHIELD -> "minecraft:shield"; case ARCHER -> "minecraft:bow"; case MEDIC -> "minecraft:golden_apple"; default -> "minecraft:iron_sword"; };
            icon(g, icon, cx + 5, cy + 4, 1);
            UiStyle.title(g, font, (on ? "✔ " : "") + kind.label, cx + 25, cy + 8);
            int ty = cy + 22;
            for (var line : font.split(Component.literal(kind.blurb), cw - 10)) {
                if (ty > cy + ch - 19) break;
                g.drawString(font, line, cx + 5, ty, UiStyle.TEXT_MUTED, false);
                ty += 9;
            }
            String kit = switch (kind) {
                case SHIELD -> s.getBoolean("shield") ? "Has a shield" : "No shield: hand it one";
                case ARCHER -> !s.getBoolean("bow") ? "No bow: hand it one" : s.getInt("arrows") == 0 ? "A bow, but no arrows" : s.getInt("arrows") + " arrows";
                case MEDIC -> s.getInt("remedies") == 0 ? "Nothing to heal with" : s.getInt("apples") + " golden apples, " + s.getInt("potions") + " potions, food";
                default -> "Blade close, bow at range";
            };
            boolean ok = switch (kind) { case SHIELD -> s.getBoolean("shield"); case ARCHER -> s.getBoolean("bow") && s.getInt("arrows") > 0; case MEDIC -> s.getInt("remedies") > 0; default -> true; };
            g.drawString(font, UiStyle.clip(font, kit, cw - 10), cx + 5, cy + ch - 11, ok ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            zone(cx, cy, cw, ch, () -> { CompoundTag t = new CompoundTag(); t.putInt("k", kind.ordinal()); PaKitClient.send("st_set", t); },
                on ? kind.label + " (its stance now)" : "Set " + kind.label + ": " + kind.blurb);
        }
        y += 2 * (ch + 4) + 4;
        // who it guards
        String ward = s.getString("ward");
        String def0 = s.getString("defending");
        UiStyle.heading(g, font, "Guards: " + ward + (def0.isEmpty() ? "" : ", defending " + def0), x, y, iw - 56);
        button(g, "Show me", x + iw - 52, y - 2, 52, 12, mx, my, true, false, "Mark who it guards (or the town it's defending)", () -> PaKitClient.send("st_show"));
        y += 13;
        int bx = x;
        int bw0 = bw("You");
        button(g, "You", bx, y, bw0, 14, mx, my, !ward.equals("you"), false, ward.equals("you") ? "already guarding you" : "It guards you", () -> ward(-1));
        bx += bw0 + 4;
        ListTag vs = s.getList("villagers", Tag.TAG_COMPOUND);
        if (vs.isEmpty()) g.drawString(font, "No villagers near you to guard.", bx + 2, y + 3, UiStyle.TEXT_FAINT, false);
        for (int i = 0; i < vs.size(); i++) {
            CompoundTag v = vs.getCompound(i);
            String n = v.getString("n");
            int b = Math.min(96, bw(n));
            if (bx + b > x + iw) break;
            int id = v.getInt("id");
            boolean cur = ward.equals(n);
            button(g, n, bx, y, b, 14, mx, my, !cur, false, cur ? "already guarding " + n : n + ", " + v.getInt("d") + " blocks from you: it guards them", () -> ward(id));
            bx += b + 4;
        }
        y += 20;
        boolean town = s.getBoolean("town");
        String tl = (town ? "✔ " : "") + "Help a town's guards when it's raided";
        button(g, tl, x, y, Math.min(iw, bw(tl) + 6), 14, mx, my, true, false,
            town ? "On: in a raid on a town near by it goes to the square and fights with the guards. Click to turn it off"
                : "Off: it stays with you in a raid. Click to turn it on",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", !town); PaKitClient.send("st_town", t); });
        y += 18;
    }

    private void ward(int id) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        PaKitClient.send("st_ward", t);
    }
}
