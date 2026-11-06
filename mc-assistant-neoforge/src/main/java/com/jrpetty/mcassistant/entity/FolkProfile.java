package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [folk-ui] Everything a folk's profile screen shows (client/FolkProfileScreen), in one tag, built when the screen asks
 * (on opening, then every few seconds while it stays open: ProfileNetwork) and never otherwise:
 * <ul>
 * <li><b>head</b>: name, nickname, age, trade and level, mood, its four needs (FolkNeeds) and what it is thinking
 *     (FolkThought);</li>
 * <li><b>about</b>: its kind (Values), traits, quirks, dream and its goals with how far on (Dreams.goal, its next level,
 *     its house), fears, likes, hobby, keepsake and story (Backstory);</li>
 * <li><b>life</b>: its family (parents, grandparents, partner, children, brothers and sisters, the ones in town a click
 *     away), its home with its address and sign (Homes.profile), its friends and rivals and how warm, what it thinks of
 *     you;</li>
 * <li><b>work</b>: trade, level and experience, knacks, wage, its record and output, what it has in hand and what it is
 *     doing now, its tools and kit;</li>
 * <li><b>day</b>: today's plan and where it is in it (FolkSchedule), the meals, its memories;</li>
 * <li><b>things</b>: what it carries and wears, its purse and net worth laid out, the comforts of its home.</li>
 * </ul>
 * Every line of its card (FolkTalk.card) rides along too, sorted onto the tab it belongs on ({@link #TAB_OF}), so
 * nothing the card says is lost to the profile.
 */
public final class FolkProfile {

    private FolkProfile() {}

    /** The profile's tabs, in order (the client's tab strip uses the same order). */
    public static final String[] TABS = { "about", "life", "work", "day", "things" };

    /** Which tab each of the card's lines belongs on (anything else: Work). */
    private static final Map<String, String> TAB_OF = Map.ofEntries(
        Map.entry("Looks", "about"), Map.entry("Nature", "about"), Map.entry("Quirks", "about"), Map.entry("Dream", "about"),
        Map.entry("Fears", "about"), Map.entry("Habits", "about"), Map.entry("Favourite place", "about"),
        Map.entry("Keepsake", "about"), Map.entry("Learning", "about"), Map.entry("Favourites", "about"),
        Map.entry("Loves", "about"), Map.entry("Feeling", "about"), Map.entry("Style", "about"), Map.entry("Birthday", "about"),
        Map.entry("Culture", "about"), Map.entry("Town ways", "about"), Map.entry("Its town", "about"),
        Map.entry("Pastimes", "about"),
        Map.entry("Home", "life"), Map.entry("Own house", "life"), Map.entry("Family", "life"), Map.entry("Household", "life"),
        Map.entry("Pet", "life"), Map.entry("Friends", "life"), Map.entry("Neighbours", "life"), Map.entry("Quarter", "life"),
        Map.entry("About town", "life"), Map.entry("Town life", "life"), Map.entry("Votes and works", "life"),
        Map.entry("The law", "life"), Map.entry("The watch", "life"), Map.entry("Health", "life"),
        Map.entry("Fire and flood", "life"), Map.entry("The war", "life"), Map.entry("In the museum", "life"),
        Map.entry("Meals", "day"), Map.entry("Remembers", "day"), Map.entry("The bell", "day"), Map.entry("Kitchen", "day"),
        Map.entry("Needs", "day"),
        Map.entry("Worth", "things"), Map.entry("Bank", "things"), Map.entry("At the counter", "things"),
        Map.entry("Comforts", "things"));

    /** The labels already drawn as widgets of their own: not repeated as card lines. */
    private static final Set<String> SHOWN = Set.of("Trade", "Friends", "Family", "Feeling", "Loves", "Remembers", "Needs", "Comforts");

    /** The most memories sent (newest first), and the most things carried listed. */
    private static final int MEMORIES = 12, ITEMS = 36;

    /** The profile of this folk as this player sees it. */
    public static CompoundTag build(VillageFolkEntity f, @Nullable ServerPlayer viewer) {
        Individual.ensure(f);
        f.ensurePersona();
        CompoundTag out = new CompoundTag();
        out.putUUID("uuid", f.getUUID());
        out.putInt("entity", f.getId());
        out.putLong("time", f.level().getDayTime());
        out.put("head", head(f));
        out.put("about", about(f));
        out.put("life", life(f, viewer));
        out.put("work", work(f));
        out.put("day", FolkSchedule.report(f));
        out.getCompound("day").put("memories", memories(f));
        out.put("things", things(f));
        out.put("card", card(f));
        return out;
    }

    // ------------------------------------------------------------------ the head

    private static CompoundTag head(VillageFolkEntity f) {
        CompoundTag h = new CompoundTag();
        Persona me = f.persona();
        h.putString("name", f.displayNameCap());
        String nick = TownSpeech.nickname(f);
        h.putString("nick", nick == null ? "" : nick);
        h.putInt("years", f.ageYears());
        h.putBoolean("child", f.isBaby());
        h.putBoolean("elder", f.isElder());
        StationTask job = f.stationTask();
        h.putString("trade", f.isBaby() ? "Child" : job == StationTask.NONE ? "No trade yet" : job.title);
        h.putInt("ordinal", job.ordinal());
        h.putInt("level", f.veteranLevel());
        h.putInt("mood", me.mood());
        h.putString("mood_word", Persona.moodWord(me.mood()));
        ListTag why = new ListTag();
        for (String w : me.moodWhy()) {
            String said = FolkTalk.reason(f, w);
            if (!said.isEmpty()) why.add(StringTag.valueOf(said));
        }
        h.put("mood_why", why);
        h.putIntArray("needs", FolkNeeds.of(f).clone());
        h.putString("thought", FolkThought.line(f));
        h.putString("doing", FolkTalk.nowDoing(f));
        h.putString("town", f.ownerId() == null ? "" : Villages.name(f.ownerId()));
        h.putString("wealth", f.isBaby() ? "" : Wealth.tier(f).label);
        h.putBoolean("visitor", Visitors.is(f));
        return h;
    }

    // ------------------------------------------------------------------ about

    private static CompoundTag about(VillageFolkEntity f) {
        CompoundTag a = new CompoundTag();
        Persona me = f.persona();
        Individual.Self s = f.individual();
        a.putString("kind", f.isBaby() ? "" : Values.describe(f));
        a.putString("traits", f.life().traitsLabel());
        a.putString("quirk", me.rolled() ? me.quirk() : "");
        a.putString("hobby", me.rolled() ? me.hobby().doing : "");
        a.putString("loves", me.rolled() ? me.loves().words : "");
        a.putString("hates", me.rolled() ? me.hates().words : "");
        a.putString("food", me.rolled() ? FolkTalk.foodWords(me.food()) : "");
        a.putString("story", s.rolled ? Backstory.tell(f) : "");
        a.putString("dream", me.rolled() ? (me.ambitionMet() ? "came true: " + me.ambition().done : me.ambition().hope) : "");
        a.put("goals", goals(f));
        return a;
    }

    /** What it is working towards, each with how far on (of 1000) and in words. */
    private static ListTag goals(VillageFolkEntity f) {
        ListTag out = new ListTag();
        Persona me = f.persona();
        if (me.rolled()) {
            Object[] g = Dreams.goal(f);
            goal(out, "Dream: " + me.ambition().hope, (Integer) g[0], (String) g[1]);
        }
        if (!f.isBaby() && f.stationTask() != StationTask.NONE && f.veteranLevel() < 50) {
            int lvl = f.veteranLevel(), xp = f.levelXp();
            int here = AssistantEntity.xpForLevel(lvl), next = AssistantEntity.xpForLevel(lvl + 1);
            goal(out, "Next level at its trade", (int) ((xp - here) * 1000L / Math.max(1, next - here)),
                "level " + lvl + " → " + (lvl + 1) + ": " + (xp - here) + " of " + (next - here));
        }
        int[] saving = Homes.savingFor(f);
        if (saving != null && saving[1] > 0 && me.ambition() != Persona.Ambition.OWN_HOUSE) {
            goal(out, "A house of its own", (int) (saving[0] * 1000L / saving[1]), saving[0] + " of " + saving[1] + " coins put by");
        }
        Individual.Self s = f.individual();
        for (Fears.Fear fear : s.fears) {
            int c = s.courage.getOrDefault(fear, 0);
            if (c > 0) goal(out, "Getting over " + fear.word, c * 1000 / Fears.COURAGE, c + " of " + Fears.COURAGE + " brave moments");
        }
        return out;
    }

    private static void goal(ListTag out, String what, int done, String words) {
        CompoundTag g = new CompoundTag();
        g.putString("what", what);
        g.putInt("done", Math.max(0, Math.min(1000, done)));
        g.putString("words", words);
        out.add(g);
    }

    // ------------------------------------------------------------------ life

    private static CompoundTag life(VillageFolkEntity f, @Nullable ServerPlayer viewer) {
        CompoundTag l = new CompoundTag();
        Social.Life life = f.life();
        UUID village = f.ownerId();
        List<VillageFolkEntity> town = new ArrayList<>();
        if (village != null) for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity v) town.add(v);
        // Its family: parents (and theirs), partner, children, brothers and sisters.
        String parents = life.parents();
        l.putString("parents", parents);
        ListTag parentTags = new ListTag(), grand = new ListTag();
        for (String name : parents.isEmpty() ? new String[0] : parents.split(" and ")) {
            VillageFolkEntity p = byName(town, name);
            parentTags.add(person(name, p, p == null ? 0 : life.affinity(p.getUUID())));
            if (p != null && !p.life().parents().isEmpty()) {
                for (String gname : p.life().parents().split(" and ")) grand.add(person(gname, byName(town, gname), 0));
            }
        }
        l.put("parent_list", parentTags);
        l.put("grandparents", grand);
        if (life.partner() != null) {
            VillageFolkEntity p = byId(town, life.partner());
            l.put("partner", person(life.partnerName(), p, life.affinity(life.partner())));
        }
        ListTag kids = new ListTag(), sibs = new ListTag();
        String me = f.displayNameCap();
        for (VillageFolkEntity o : town) {
            if (o == f) continue;
            String op = o.life().parents();
            if (op.isEmpty()) continue;
            if ((" and " + op + " and ").contains(" and " + me + " and ")) kids.add(withAge(person(o.displayNameCap(), o, life.affinity(o.getUUID())), o));
            else if (!parents.isEmpty() && op.equals(parents)) sibs.add(withAge(person(o.displayNameCap(), o, life.affinity(o.getUUID())), o));
        }
        l.put("children", kids);
        l.putInt("children_all", life.children());
        l.put("siblings", sibs);
        // Friends and rivals, warmest and coldest first.
        ListTag friends = new ListTag(), rivals = new ListTag();
        List<Map.Entry<UUID, Social.Bond>> bonds = new ArrayList<>(life.bonds.entrySet());
        bonds.sort((a, b) -> Integer.compare(b.getValue().affinity, a.getValue().affinity));
        for (Map.Entry<UUID, Social.Bond> e : bonds) {
            if (e.getKey().equals(life.partner())) continue;
            int aff = e.getValue().affinity;
            if (aff >= Social.FRIEND && friends.size() < 10) friends.add(bond(e.getKey(), e.getValue(), town));
        }
        for (int i = bonds.size() - 1; i >= 0 && rivals.size() < 6; i--) {
            Map.Entry<UUID, Social.Bond> e = bonds.get(i);
            if (e.getValue().affinity <= Social.RIVAL) rivals.add(bond(e.getKey(), e.getValue(), town));
        }
        l.put("friends", friends);
        l.put("rivals", rivals);
        l.putInt("friend_at", Social.FRIEND);
        l.putInt("close_at", Social.CLOSE);
        // Its home.
        l.put("home", Homes.profile(f));
        // What it thinks of you.
        if (viewer != null) {
            Persona p = f.persona();
            int aff = p.affinity(viewer.getUUID());
            CompoundTag you = new CompoundTag();
            you.putInt("affinity", aff);
            you.putString("standing", Persona.standing(aff));
            you.putBoolean("knows", p.knows(viewer.getUUID()));
            you.putString("heard_from", p.heardFrom(viewer.getUUID()));
            l.put("you", you);
        }
        return l;
    }

    private static CompoundTag bond(UUID id, Social.Bond b, List<VillageFolkEntity> town) {
        VillageFolkEntity o = byId(town, id);
        CompoundTag c = person(b.name, o, b.affinity);
        c.putUUID("uuid", id);
        return c;
    }

    private static CompoundTag person(String name, @Nullable VillageFolkEntity o, int affinity) {
        CompoundTag c = new CompoundTag();
        c.putString("name", name);
        c.putInt("affinity", affinity);
        if (o != null) {
            c.putUUID("uuid", o.getUUID());
            c.putString("trade", o.isBaby() ? "Child" : o.stationTask().title);
            c.putInt("ordinal", o.stationTask().ordinal());
        } else {
            c.putBoolean("gone", true);                                  // not in the town now: away, moved on, or dead
        }
        return c;
    }

    private static CompoundTag withAge(CompoundTag c, VillageFolkEntity o) {
        c.putInt("years", o.ageYears());
        return c;
    }

    @Nullable
    private static VillageFolkEntity byName(List<VillageFolkEntity> town, String name) {
        for (VillageFolkEntity o : town) if (o.displayNameCap().equals(name)) return o;
        return null;
    }

    @Nullable
    private static VillageFolkEntity byId(List<VillageFolkEntity> town, UUID id) {
        for (VillageFolkEntity o : town) if (o.getUUID().equals(id)) return o;
        return null;
    }

    // ------------------------------------------------------------------ work

    private static CompoundTag work(VillageFolkEntity f) {
        CompoundTag w = new CompoundTag();
        StationTask job = f.stationTask();
        w.putString("trade", f.isBaby() ? "Child" : job == StationTask.NONE ? "No trade yet" : job.title);
        int lvl = f.veteranLevel(), xp = f.levelXp();
        w.putInt("level", lvl);
        w.putInt("xp", xp);
        w.putInt("xp_here", AssistantEntity.xpForLevel(lvl));
        w.putInt("xp_next", AssistantEntity.xpForLevel(lvl + 1));
        w.putString("trades", f.tradeLevels());
        w.putString("knacks", FolkSkills.cardLine(f));
        w.putInt("wage", f.isBaby() ? 0 : Wealth.wage(f));
        w.putString("wage_why", JobWorth.cardLine(f));
        w.putInt("made_yesterday", Economy.madeYesterday(f));
        w.putInt("earned", f.earnedInAll());
        ListTag record = new ListTag();
        for (String r : f.workRecord(8)) record.add(StringTag.valueOf(r));
        w.put("record", record);
        Job now = f.peekJob();
        w.putString("task", now == null ? "" : FolkTalk.cap(now.label()));
        w.putString("doing", FolkTalk.nowDoing(f));
        w.putString("status", f.clientStatus());
        w.putString("pace", f.isBaby() || job == StationTask.NONE ? "" : f.paceLine());
        w.putString("shift", f.shift().label);
        ListTag missing = new ListTag();
        for (String m : f.missingEssentials()) missing.add(StringTag.valueOf(m));
        w.put("missing", missing);
        ItemStack hand = f.getMainHandItem();
        w.putString("tool", hand.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(hand.getItem()).toString());
        return w;
    }

    // ------------------------------------------------------------------ the day

    private static ListTag memories(VillageFolkEntity f) {
        ListTag out = new ListTag();
        List<Persona.Memory> mem = f.persona().memories();
        for (int i = mem.size() - 1; i >= 0 && out.size() < MEMORIES; i--) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", mem.get(i).day());
            c.putString("text", mem.get(i).text());
            c.putInt("weight", mem.get(i).weight());
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ its things

    private static CompoundTag things(VillageFolkEntity f) {
        CompoundTag t = new CompoundTag();
        ListTag items = new ListTag();
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty()) continue;
            if (items.size() >= ITEMS) break;
            items.add(item(s));
        }
        t.put("items", items);
        CompoundTag worn = new CompoundTag();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = f.getItemBySlot(slot);
            if (!s.isEmpty()) worn.put(slot.getName(), item(s));
        }
        t.put("worn", worn);
        t.put("home", homeThings(f));
        t.putString("home_words", Decor.cardLine(f));
        t.putInt("purse", f.purse());
        if (!f.isBaby()) {
            t.putInt("house_fund", Homes.savedShare(f));
            t.putInt("house_owned", Homes.ownedShare(f));
            t.putInt("goods", Wealth.belongings(f));
            t.putInt("comforts", f.comforts());
            t.putInt("comforts_worth", f.comforts() * 3);
            t.putInt("bank", Bank.worthOf(f));
            t.putInt("worth", Wealth.worth(f));
            t.putString("tier", Wealth.tier(f).label);
        }
        return t;
    }

    /**
     * What it keeps at home, as items (id, count, name, worth), for the Belongings tab: the one place the profile reads a
     * home's possessions, to be pointed at the homes' own possessions (chests, larder, wardrobe, shelves) when they are
     * kept. Until then, nothing: the tab shows the comforts of its home in words (Decor.cardLine) instead.
     */
    static ListTag homeThings(VillageFolkEntity f) {
        return Belongings.profileItems(f);                // [shops-homes] the home's chest, larder, wardrobe and toy box
    }

    private static CompoundTag item(ItemStack s) {
        CompoundTag c = new CompoundTag();
        c.putString("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        c.putInt("n", s.getCount());
        c.putString("name", s.getHoverName().getString());
        c.putInt("worth", (int) Math.round(Wealth.value(s)));
        if (Homes.isKeepsake(s) || Individual.isTreasure(s)) c.putBoolean("keepsake", true);
        return c;
    }

    // ------------------------------------------------------------------ its card, sorted

    private static CompoundTag card(VillageFolkEntity f) {
        CompoundTag out = new CompoundTag();
        for (String tab : TABS) out.put(tab, new ListTag());
        String card = FolkTalk.card(f);
        if (card.isEmpty()) return out;
        String fallback = Visitors.is(f) || Newcomers.is(f) ? "about" : "work";
        for (String line : card.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String label = line.substring(0, bar);
            if (SHOWN.contains(label)) continue;
            CompoundTag c = new CompoundTag();
            c.putString("k", label);
            c.putString("v", line.substring(bar + 1));
            out.getList(TAB_OF.getOrDefault(label, fallback), 10).add(c);
        }
        return out;
    }
}
