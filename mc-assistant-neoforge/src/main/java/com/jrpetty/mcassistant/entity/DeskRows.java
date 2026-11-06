package com.jrpetty.mcassistant.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * [shops-homes] The body of a plain service screen (the bank, the tavern, the smithy and the tailor's), built the same
 * way for each: sections, each a few lines of reading and a few rows, a row being a thing to do (its button), what it
 * is, what it costs, an amount to choose where it takes one, and why it cannot be done when it cannot. The client's
 * DeskPanel draws whatever it is given, so a desk is only this data and its act.
 */
final class DeskRows {

    private final CompoundTag body = new CompoundTag();
    private final ListTag sections = new ListTag();
    @Nullable private CompoundTag section;
    private final HolderLookup.Provider reg;

    DeskRows(HolderLookup.Provider reg) {
        this.reg = reg;
        body.put("sections", sections);
    }

    /** A new section under this heading. */
    DeskRows section(String title) {
        section = new CompoundTag();
        section.putString("title", title);
        section.put("lines", new ListTag());
        section.put("rows", new ListTag());
        sections.add(section);
        return this;
    }

    /** A line of reading in the section. */
    DeskRows line(String text) {
        if (section == null) section("");
        if (text != null && !text.isBlank()) section.getList("lines", 8).add(StringTag.valueOf(text));
        return this;
    }

    /** A row: its action, the button's word, what it is, its icon, and why not (empty: it can be done). */
    CompoundTag row(String action, String button, String label, String sub, ItemStack icon, String whyNot) {
        if (section == null) section("");
        CompoundTag r = new CompoundTag();
        r.putString("action", action);
        r.putString("button", button);
        r.putString("label", label);
        r.putString("sub", sub);
        if (!icon.isEmpty()) r.put("icon", icon.save(reg));
        r.putBoolean("enabled", whyNot == null || whyNot.isEmpty());
        r.putString("why", whyNot == null ? "" : whyNot);
        r.put("args", new CompoundTag());
        section.getList("rows", 10).add(r);
        return r;
    }

    /** An amount to choose for a row: from, to, starting at, in steps of. */
    static CompoundTag amount(CompoundTag row, int min, int max, int start, int step) {
        CompoundTag a = new CompoundTag();
        a.putInt("min", min);
        a.putInt("max", Math.max(min, max));
        a.putInt("start", Math.max(min, Math.min(Math.max(min, max), start)));
        a.putInt("step", Math.max(1, step));
        row.put("amount", a);
        return row;
    }

    CompoundTag build() {
        return body;
    }
}
