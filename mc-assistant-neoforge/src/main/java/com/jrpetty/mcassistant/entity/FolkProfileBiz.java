package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [biz30] Two more pages of a folk's profile (FolkProfile, client FolkProfileScreen): its <b>businesses</b> and its place in
 * the <b>town</b>.
 * <ul>
 * <li>Every business it owns, and every one it works in (a hand of a firm, the town's own counter, a player's shop it keeps):
 *     its name and trade, where it is, its role there; this week's takings and profit, its till, its hands; how long it has
 *     run and whether it is growing or struggling; whether the viewer may open its books (the town's books' own rule,
 *     Businesses.mayRead) and its id to open them by. Then its plans: a business it means to open tomorrow, another it is
 *     thinking of, or why not yet.</li>
 * <li>Its standing in the town: a citizen of it, a visitor; the offices it holds; the petitions it has signed; what it
 *     thinks of the viewer and how to raise it; and the folk it is warmest and coldest to. And a short log of its recent
 *     life (its memories that mattered most: a wedding, a business opened, a new house, a step up).</li>
 * </ul>
 * Built with the rest of the profile, when asked (every few seconds while the screen is open), into the one packet.
 */
public final class FolkProfileBiz {

    private FolkProfileBiz() {}

    /** The businesses page. */
    public static CompoundTag business(VillageFolkEntity f, @Nullable ServerPlayer viewer) {
        CompoundTag out = new CompoundTag();
        ListTag firms = new ListTag();
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) {
            out.put("firms", firms);
            return out;
        }
        long day = Businesses.today(level);
        Business works = Businesses.employer(village, f);
        List<Business> seen = new ArrayList<>();
        for (Business b : Businesses.all(village)) {
            if (!b.open) continue;
            boolean owns = b.ownedBy(f.getUUID());
            boolean hand = works == b || Enterprise.hands(village, b).contains(f);
            if (!owns && !hand) continue;
            seen.add(b);
            BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
            CompoundTag c = firm(level, village, b, day, viewer);
            c.putString("trade", t != null ? t.title() : b.type.title);
            c.putString("role", owns && hand ? "owner, and works there" : owns ? (b.owners.size() > 1 ? "part-owner" : "owner") : "a hand");
            firms.add(c);
        }
        for (PlayerShops.Shop s : PlayerShops.all(village)) {
            if (!f.getUUID().equals(s.keeper)) continue;
            CompoundTag c = new CompoundTag();
            c.putString("name", s.name);
            c.putString("trade", ShopNeeds.Kind.of(s.kind).label + ", " + s.ownerName + "'s shop");
            c.putString("role", "keeps it for " + s.ownerName + " (" + s.wage + "c a day)");
            c.putString("where", where(level, village, s.anchor));
            c.putLong("takings", java.util.Arrays.stream(s.weekCoin).sum() * 100L);
            c.putLong("till", s.till * 100L);
            c.putInt("staff", 1);
            c.putLong("days", Math.max(0, day - s.since));
            c.putString("trend", "");
            firms.add(c);
        }
        out.put("firms", firms);
        out.putString("plans", plans(level, village, f));
        out.putString("owns_line", FirmBranches.line(level, village, f.getUUID()));
        return out;
    }

    /** One business's row. */
    static CompoundTag firm(ServerLevel level, UUID village, Business b, long day, @Nullable ServerPlayer viewer) {
        CompoundTag c = new CompoundTag();
        c.putString("id", b.id);
        c.putString("name", b.name);
        c.putString("where", b.at == null ? "" : where(level, village, b.at));
        Business.Day week = b.last(day, 7), before = b.last(day - 7, 7);
        c.putLong("takings", week.revenue);
        c.putLong("profit", week.net());
        c.putLong("till", b.till);
        c.putInt("staff", Enterprise.isFirm(b) ? Enterprise.hands(village, b).size() : Businesses.staff(village, b).size());
        c.putLong("days", Math.max(0, day - b.opened));
        String trend = day - b.opened < 10 ? "new" : week.net() > before.net() + 50 && week.net() > 0 ? "growing"
            : week.net() < 0 ? "struggling" : "steady";
        c.putString("trend", trend);
        c.putBoolean("books", viewer != null && Businesses.mayRead(level, village, b, viewer));
        return c;
    }

    /** Where a place is, in the town's words: its street address if it has one, else a bearing from the square. */
    static String where(ServerLevel level, UUID village, BlockPos at) {
        Villages.Village v = Villages.get(village);
        for (Ledger.Building b : Ledger.buildings(village)) {
            int r = com.jrpetty.mcassistant.entity.goal.BuildGoal.halfOf(b.structure()) + 1;
            if (Math.abs(b.anchor().getX() - at.getX()) <= r && Math.abs(b.anchor().getZ() - at.getZ()) <= r) {
                String[] a = TownLife.address(village, v == null ? at : v.centre(), b);
                String name = Villages.spoken(b.structure());
                return a == null ? "in " + name : "in " + name + ", " + a[0] + " " + a[1];
            }
        }
        if (v == null) return at.getX() + ", " + at.getZ();
        int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        if (Math.abs(dx) <= 14 && Math.abs(dz) <= 14) return "on the square";
        String ns = dz < -6 ? "north" : dz > 6 ? "south" : "", ew = dx > 6 ? "east" : dx < -6 ? "west" : "";
        return "to the " + (ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew) + " of the square";
    }

    /** What it means to do next with its money: a business tomorrow, another it is thinking of, or why not yet. */
    static String plans(ServerLevel level, UUID village, VillageFolkEntity f) {
        CompoundTag in = Enterprise.data(village).getCompound("intent");
        if (in.hasUUID("founder") && in.getUUID("founder").equals(f.getUUID())) {
            BusinessTypes.BusinessType t = BusinessTypes.of(in.getString("type"));
            if (t != null) return "Means to open a " + t.noun() + " tomorrow.";
        }
        List<Business> mine = FirmBranches.owned(village, f.getUUID());
        if (mine.isEmpty()) return "";
        String why = FirmBranches.whyNot(level, village, f);
        BusinessTypes.BusinessType t = Enterprise.kindOf(village, mine.get(0));
        String noun = t == null ? "business" : t.noun();
        if (why == null) return "Thinking of opening " + (mine.size() == 1 ? "a second " : "another ") + noun + ", if the town wants one.";
        return "Not opening another just yet: " + why + ".";
    }

    /** The town page: its standing, offices, petitions, what it thinks of the viewer, its warmest and coldest. */
    public static CompoundTag civic(VillageFolkEntity f, @Nullable ServerPlayer viewer) {
        CompoundTag out = new CompoundTag();
        UUID village = f.ownerId();
        out.putString("town", village == null ? "" : Villages.name(village));
        out.putString("status", f.isBaby() ? "a child of the town" : Visitors.is(f) ? "a visitor" : village == null ? "of no town" : "a citizen");
        ListTag offices = new ListTag();
        for (Offices.Office o : Offices.of(f)) offices.add(StringTag.valueOf(capital(o.words)));
        if (village != null && Orders.elderOf(village) == f) offices.add(StringTag.valueOf("The town's leader"));
        if (village != null && Council.members(village).contains(f)) offices.add(StringTag.valueOf("A member of the council"));
        out.put("offices", offices);
        if (viewer != null) {
            int aff = f.persona().affinity(viewer.getUUID());
            out.putInt("you", aff);
            out.putString("you_word", Persona.standing(aff));
            out.putString("raise", aff >= 60 ? "It thinks the world of you already."
                : "Talk with it each day (a little each time), give it what it loves" + (f.persona().rolled() ? " (" + f.persona().loves().words + ")" : "")
                + ", help with its work and the town's (a shift on the jobs board), and keep your word; a gift it hates, or a blow, sets it back.");
        }
        ListTag hist = new ListTag();
        List<Persona.Memory> mem = f.persona().memories();
        mem.sort(java.util.Comparator.comparingLong((Persona.Memory m) -> -m.day()));
        for (Persona.Memory m : mem) {
            if (hist.size() >= 8) break;
            if (m.weight() < 2) continue;
            hist.add(StringTag.valueOf("Day " + (m.day() + 1) + ": " + m.text()));
        }
        out.put("history", hist);
        return out;
    }

    static String capital(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.startsWith("the ") ? s.substring(4) : s;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

}
