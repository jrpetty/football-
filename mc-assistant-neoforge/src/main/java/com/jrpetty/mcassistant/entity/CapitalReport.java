package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [capital] The Capital page of the town's books (client CapitalPage): the savings club or the bank (its deposits, its
 * loans and their rate, what it lent and refused and why, its defaults), the bourse (each firm's last price against its
 * worth, the day's volume, the dividends and the holders), the land register (the titles, what they are worth, the land
 * index and the rents that follow it, the idle plots), and the town's debt (its bonds, their rate and room, the coupons,
 * the IOUs to its market). Also the folk card's Capital line.
 */
public final class CapitalReport {

    private CapitalReport() {}

    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        boolean live = MarketMaker.live(village);
        t.putBoolean("live", live);
        if (!live) {
            t.putString("state", "No capital markets yet: the town's savings club, its bourse, its land register and its bonds open with its market.");
            return t;
        }
        t.put("credit", FolkCredit.report(level, village));
        t.put("bourse", Bourse.report(level, village));
        t.put("land", LandTitles.report(village));
        t.put("bonds", TownBonds.report(village));
        ListTag days = new ListTag();
        for (CompoundTag d : CapitalBook.days(village)) days.add(d.copy());
        t.put("days", days);
        ListTag lines = new ListTag();
        for (String s : lines(level, village)) lines.add(StringTag.valueOf(s));
        t.put("lines", lines);
        return t;
    }

    /** The town's capital in a few lines (/village capital, the tests' logs). */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        out.add(FolkCredit.line(level, village));
        out.add(Bourse.line(level, village));
        out.add(LandTitles.line(village));
        out.add(TownBonds.line(village));
        return out;
    }

    /** The folk card's Capital line: its savings, its shares, its bonds; "" if it has none. */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !MarketMaker.live(id)) return "";
        List<String> parts = new ArrayList<>();
        String s = FolkCredit.cardLine(f);
        if (!s.isEmpty()) parts.add(s);
        s = Bourse.cardLine(f);
        if (!s.isEmpty()) parts.add(s);
        s = TownBonds.cardLine(f);
        if (!s.isEmpty()) parts.add(s);
        return String.join("; ", parts);
    }
}
