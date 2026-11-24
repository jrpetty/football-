package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaMemory.Book;
import com.jrpetty.mcassistant.pa.PaMemory.Entry;
import com.jrpetty.mcassistant.pa.PaMemory.Kind;
import com.jrpetty.mcassistant.pa.PaMemory.When;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** [pa35] An assistant's memory saved with its record and read back (kept apart so PaMemory stays plain Java). */
public final class PaMemoryNbt {

    private PaMemoryNbt() {}

    public static CompoundTag save(Book b) {
        CompoundTag t = new CompoundTag();
        t.putInt("Next", b.next);
        t.putBoolean("Paused", b.paused);
        ListTag l = new ListTag();
        for (Entry e : b.entries) l.add(save(e));
        t.put("E", l);
        return t;
    }

    public static void load(Book b, CompoundTag t) {
        b.entries.clear();
        b.next = Math.max(1, t.getInt("Next"));
        b.paused = t.getBoolean("Paused");
        for (Tag x : t.getList("E", Tag.TAG_COMPOUND)) {
            Entry e = load((CompoundTag) x);
            if (e != null) { b.entries.add(e); b.next = Math.max(b.next, e.id + 1); }
        }
    }

    static CompoundTag save(Entry e) {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", e.id);
        t.putString("K", e.kind.name());
        t.putString("S", e.subject);
        if (!e.value.isEmpty()) t.putString("V", e.value);
        if (e.when != When.NONE) t.putString("W", e.when.name());
        if (!e.extra.isEmpty()) t.putString("X", e.extra);
        if (e.n != 0) t.putInt("N", e.n);
        if (e.due >= 0) t.putLong("Due", e.due);
        t.putString("Said", e.said);
        t.putLong("Made", e.made);
        return t;
    }

    static Entry load(CompoundTag t) {
        Entry e = new Entry();
        try {
            e.kind = Kind.valueOf(t.getString("K"));
        } catch (Exception ex) {
            return null;                                    // a kind from a later version: dropped, not the book
        }
        e.id = t.getInt("Id");
        e.subject = t.getString("S");
        e.value = t.getString("V");
        e.when = t.contains("W") ? When.of(t.getString("W")) : When.NONE;
        e.extra = t.getString("X");
        e.n = t.getInt("N");
        e.due = t.contains("Due") ? t.getLong("Due") : -1;
        e.said = t.getString("Said");
        e.made = t.getLong("Made");
        return e;
    }
}
