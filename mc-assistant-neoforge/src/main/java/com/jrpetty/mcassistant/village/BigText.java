package com.jrpetty.mcassistant.village;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * [health] Text of any length kept in a save. A string tag is written in modified UTF-8 with a two-byte length, so one
 * over 65,535 bytes is not saved at all: the game logs "Failed to write NBT String" and writes an empty string in its
 * place, and the text is lost at the next load (a sleeping town's parked chunk requests, "lod.parked", reached 98,799
 * bytes in a long client run). Text that fits is written as a string, as it always was; longer text as a list of string
 * pieces, each well inside the limit. Reading takes either, so old saves load as before.
 */
public final class BigText {

    private BigText() {}

    /** The most bytes a single string tag is let hold here (the format's limit is 65,535). */
    public static final int MAX_BYTES = 60000;
    /** Characters per piece of a long text: at most three bytes each in modified UTF-8, so a piece is under 48,000. */
    static final int PIECE = 16000;

    /** Put text under a key: a string while it fits, else its pieces. */
    public static void put(CompoundTag tag, String key, String value) {
        if (value == null) value = "";
        if (encodedLength(value) <= MAX_BYTES) {
            tag.putString(key, value);
            return;
        }
        ListTag pieces = new ListTag();
        int at = 0, n = value.length();
        while (at < n) {
            int end = Math.min(n, at + PIECE);
            if (end < n && Character.isHighSurrogate(value.charAt(end - 1))) end--;   // a pair kept whole
            pieces.add(StringTag.valueOf(value.substring(at, end)));
            at = end;
        }
        tag.put(key, pieces);
    }

    /** The text under a key, written either way ("" if none). */
    public static String get(CompoundTag tag, String key) {
        if (tag.contains(key, Tag.TAG_LIST)) {
            ListTag pieces = tag.getList(key, Tag.TAG_STRING);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pieces.size(); i++) sb.append(pieces.getString(i));
            return sb.toString();
        }
        return tag.getString(key);
    }

    /** Bytes the text takes in a save (modified UTF-8, as DataOutput.writeUTF counts them). */
    public static long encodedLength(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c >= 0x0001 && c <= 0x007F ? 1 : c <= 0x07FF ? 2 : 3;
        }
        return n;
    }
}
