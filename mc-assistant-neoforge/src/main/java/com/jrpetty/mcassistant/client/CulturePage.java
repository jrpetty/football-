package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Culture page of the town's books (CityScreen) [batchD], as the server's Culture.report has it: the
 * town's banner drawn large with what it is in words, where it hangs and what it waits on; its motto and
 * whether it is carved over the hall's door; its customs and when each is next kept; the theatre and its
 * plays; the band and the choir; the pictures and where they hang; and the plaques. Scrolls with the wheel.
 */
public final class CulturePage {

    private CulturePage() {}

    private static final int ROW = 10;

    /** One line of the page: a heading ('H'), a line of words ('N'), a quiet one ('M'), a good one ('G'), a warning ('W'). */
    private record Line(char kind, FormattedCharSequence text) {}

    /** The page, drawn from the scroll'th line down under the banner. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag c, int x, int y, int w, int h, int scroll, int mx, int my) {
        CompoundTag banner = c.getCompound("banner");
        ItemStack flag = banner(banner);
        int top = y;
        // The banner, three times the size of an item, and beside it what it is.
        if (!flag.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 2, y, 0);
            g.pose().scale(3.0F, 3.0F, 1.0F);
            g.renderItem(flag, 0, 0);
            g.pose().popPose();
        }
        int tx = x + 56, tw = w - 58;
        int ty = y;
        g.drawString(font, "The town's banner", tx, ty, Ui.INK, false);
        ty += 11;
        for (FormattedCharSequence l : TextCache.split(font, banner.getString("blazon"), (int) (tw / 0.75F))) {
            small(g, font, l, tx, ty, Ui.MUTED);
            ty += 8;
            if (ty > top + 34) break;
        }
        String motto = banner.getString("motto");
        if (!motto.isEmpty()) {
            g.drawString(font, Ui.clip(font, "“" + motto + "”", tw), tx, ty + 1, Ui.ACCENT, false);
            ty += 11;
        }
        int hung = banner.getInt("hung"), places = banner.getInt("places");
        List<String> where = strings(banner, "where");
        String state = places == 0 ? "Hangs nowhere yet: it goes up on the hall, the gates, the market and the theatre as they stand."
            : "Hung " + hung + " of " + places + (where.isEmpty() ? "" : ": " + String.join(", ", where)) + "."
            + (banner.getString("short").isEmpty() ? "" : " Waiting on " + banner.getString("short") + ".");
        small(g, font, Ui.clip(font, state, (int) (tw / 0.75F)), tx, ty, banner.getString("short").isEmpty() ? Ui.GOOD : Ui.WARN);
        ty += 9;
        y = Math.max(y + 52, ty + 4);
        int ch = h - (y - top);

        List<Line> lines = new ArrayList<>();
        int width = (int) ((w - 8) / 0.75F);
        if (!motto.isEmpty()) {
            add(lines, font, 'N', "Our motto, “" + motto + "”, " + (banner.getBoolean("carved")
                ? "is carved over the door of " + banner.getString("hall") + "." : banner.getString("hall").isEmpty()
                ? "will be carved over the hall's door once the town has a hall." : "is to be carved over the door of " + banner.getString("hall") + "."), width);
        }
        if (banner.contains("price")) {
            add(lines, font, 'M', "A copy of the banner: " + banner.getInt("price") + " coins at the shop's sign, for the town's citizens"
                + (banner.getBoolean("shop") ? "" : " (once there is a shop)") + "; " + banner.getInt("sold") + " sold so far"
                + (banner.getString("tailor").isEmpty() ? "." : ". The tailor, " + banner.getString("tailor") + ", weaves them."), width);
        }
        section(lines, font, "Customs: the town's great days, kept every year", c.getCompound("customs"), width,
            "None yet: the founding, a raid beaten off, a great storm or the first diamond each become one.");
        section(lines, font, "The theatre", c.getCompound("theatre"), width, "");
        section(lines, font, "The band and the choir", c.getCompound("music"), width, "");
        section(lines, font, "Paintings", c.getCompound("paintings"), width, "");
        section(lines, font, "Plaques", c.getCompound("plaques"), width, "");
        section(lines, font, "The town's arms, beyond its hall", c.getCompound("arms"), width, "");       // [arms]
        section(lines, font, "Buskers", c.getCompound("buskers"), width, "");                             // [arms]
        // [culture2] The town's own ways (TownWays): its table, its tongue, its building, its feast and its faith.
        CompoundTag ways = c.getCompound("ways");
        section(lines, font, "Our own ways", ways.getCompound("ways"), width, "Worked out once its land is known.");
        section(lines, font, "Our table: the town's own dish", ways.getCompound("table"), width, "");
        section(lines, font, "Our tongue: greetings, words, sayings, nicknames", ways.getCompound("tongue"), width, "");
        section(lines, font, "How we build", ways.getCompound("building"), width, "");
        section(lines, font, "Our own festival", ways.getCompound("feast"), width, "");
        section(lines, font, "Our faith and its rites", ways.getCompound("faith"), width, "");

        int rows = Math.max(1, ch / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        for (int i = start; i < lines.size() && cy + ROW <= y + ch; i++) {
            Line l = lines.get(i);
            switch (l.kind()) {
                case 'H' -> {
                    g.fill(x, cy + 2, x + 2, cy + 9, Ui.ACCENT);
                    g.drawString(font, l.text(), x + 5, cy + 1, Ui.FAINT, false);
                }
                case 'G' -> small(g, font, l.text(), x + 4, cy + 2, Ui.GOOD);
                case 'W' -> small(g, font, l.text(), x + 4, cy + 2, Ui.WARN);
                case 'M' -> small(g, font, l.text(), x + 4, cy + 2, Ui.FAINT);
                default -> small(g, font, l.text(), x + 4, cy + 2, Ui.INK);
            }
            cy += ROW;
        }
        if (lines.size() > rows) small(g, font, FormattedCharSequence.forward("(scroll for more)", net.minecraft.network.chat.Style.EMPTY),
            x + w - 70, y + ch - 8, Ui.FAINT);
        // The banner's own words, with the mouse over it.
        if (!flag.isEmpty() && mx >= x && mx < x + 52 && my >= top && my < top + 50) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("The town's banner"));
            tip.add(Component.literal(banner.getString("blazon")));
            return tip;
        }
        return null;
    }

    private static void section(List<Line> lines, Font font, String heading, CompoundTag t, int width, String none) {
        lines.add(new Line('H', FormattedCharSequence.forward(heading, net.minecraft.network.chat.Style.EMPTY)));
        List<String> body = strings(t, "lines");
        if (body.isEmpty() && !none.isEmpty()) add(lines, font, 'M', none, width);
        for (String s : body) {
            char kind = s.contains("today!") || s.startsWith("on now") || s.startsWith("the theatre stands") ? 'G'
                : s.startsWith("waiting on") || s.startsWith("the band waits") ? 'W' : 'N';
            add(lines, font, kind, "• " + Character.toUpperCase(s.charAt(0)) + s.substring(1), width);
        }
    }

    private static void add(List<Line> lines, Font font, char kind, String text, int width) {
        for (FormattedCharSequence l : TextCache.split(font, text, width)) lines.add(new Line(kind, l));
    }

    /** The banner as an item, its patterns woven in from the client's own registry of them. */
    static ItemStack banner(CompoundTag b) {
        DyeColor field = DyeColor.byName(b.getString("field"), null);
        if (field == null) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(field.getName() + "_banner")));
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return s;
        var reg = mc.level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        ListTag l = b.getList("layers", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(t.getString("pattern"));
            DyeColor colour = DyeColor.byName(t.getString("colour"), null);
            if (id == null || colour == null) continue;
            reg.getHolder(ResourceKey.create(Registries.BANNER_PATTERN, id)).ifPresent(h -> layers.add(h, colour));
        }
        s.set(DataComponents.BANNER_PATTERNS, layers.build());
        return s;
    }

    private static List<String> strings(CompoundTag m, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

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
}
