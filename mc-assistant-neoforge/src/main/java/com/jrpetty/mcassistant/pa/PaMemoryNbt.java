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

    /** [pa35] The bond: its number, favourites, want, firsts, our story, when its player was last seen. */
    public static CompoundTag saveBond(PaBond.State s) {
        CompoundTag t = new CompoundTag();
        t.putDouble("B", s.bond);
        t.putString("Want", s.want);
        t.putLong("WantSince", s.wantSince);
        t.putLong("WantAgain", s.wantAgainAt);
        t.putLong("Seen", s.lastSeen);
        ListTag f = new ListTag();
        for (String x : s.favourites) f.add(net.minecraft.nbt.StringTag.valueOf(x));
        t.put("Fav", f);
        ListTag fi = new ListTag();
        for (String x : s.firsts) fi.add(net.minecraft.nbt.StringTag.valueOf(x));
        t.put("Firsts", fi);
        ListTag st = new ListTag();
        for (String[] e : s.story) { CompoundTag c = new CompoundTag(); c.putString("D", e[0]); c.putString("W", e[1]); c.putString("T", e[2]); st.add(c); }
        t.put("Story", st);
        return t;
    }

    public static void loadBond(PaBond.State s, CompoundTag t) {
        s.bond = t.contains("B") ? t.getDouble("B") : 5;
        s.want = t.getString("Want");
        s.wantSince = t.getLong("WantSince");
        s.wantAgainAt = t.getLong("WantAgain");
        s.lastSeen = t.getLong("Seen");
        s.favourites.clear();
        for (Tag x : t.getList("Fav", Tag.TAG_STRING)) s.favourites.add(x.getAsString());
        s.firsts.clear();
        for (Tag x : t.getList("Firsts", Tag.TAG_STRING)) s.firsts.add(x.getAsString());
        s.story.clear();
        for (Tag x : t.getList("Story", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; s.story.add(new String[]{c.getString("D"), c.getString("W"), c.getString("T")}); }
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
