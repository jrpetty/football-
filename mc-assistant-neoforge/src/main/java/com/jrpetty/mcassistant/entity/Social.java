package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who a folk is, and who it gets on with.
 *
 * <p>A village of identical workers is a machine. Each folk is born with two traits —
 * a hard worker or an easygoing one, sociable or shy, cheerful or grumpy, generous,
 * curious — and the traits change what it does with its time: how long its break is,
 * whether it seeks company at the end of the day, whether it hands a ration to a
 * hungry friend, how fast it warms to people. Out of the time folk spend near one
 * another come friendships (and, between two grumps, rivalries), and out of the
 * closest of those come partners, who raise children together, take their breaks at
 * the same hour, and spend their evenings side by side. Children take after a parent.
 *
 * <p>All of it is the folk's own: carried on the folk and saved with it, never
 * spoken in chat. It shows in what they do — who walks over to whom at dusk, the
 * hearts and the happy (or angry) faces when they meet — on the folk's own screen,
 * and in {@code /village people}.
 */
public final class Social {

    private Social() {}

    /** Warm enough to be friends, close friends, and cold enough to be rivals. */
    public static final int FRIEND = 30, CLOSE = 60, RIVAL = -30;

    public enum Trait {
        HARDWORKING("hardworking"),
        EASYGOING("easygoing"),
        SOCIABLE("sociable"),
        SHY("shy"),
        CHEERFUL("cheerful"),
        GRUMPY("grumpy"),
        GENEROUS("generous"),
        CURIOUS("curious");

        public final String label;

        Trait(String label) { this.label = label; }

        /** The trait nobody can have alongside this one. */
        @Nullable
        public Trait opposite() {
            return switch (this) {
                case HARDWORKING -> EASYGOING;
                case EASYGOING -> HARDWORKING;
                case SOCIABLE -> SHY;
                case SHY -> SOCIABLE;
                case CHEERFUL -> GRUMPY;
                case GRUMPY -> CHEERFUL;
                default -> null;
            };
        }

        public String title() {
            return Character.toUpperCase(label.charAt(0)) + label.substring(1);
        }
    }

    /** What one folk feels for another: a name to call it by, and how warmly. */
    public static final class Bond {
        public String name;
        public int affinity;

        Bond(String name, int affinity) {
            this.name = name;
            this.affinity = affinity;
        }
    }

    /** One folk's side of the village's social life. */
    public static final class Life {
        final List<Trait> traits = new ArrayList<>(2);
        final Map<UUID, Bond> bonds = new HashMap<>();
        @Nullable UUID partner;
        String partnerName = "";
        /** "Ash and Elm", or empty for a founder. */
        String parents = "";
        int children;

        public List<Trait> traits() { return traits; }

        public boolean has(Trait t) { return traits.contains(t); }

        @Nullable public UUID partner() { return partner; }

        public String partnerName() { return partnerName; }

        public String parents() { return parents; }

        public int children() { return children; }

        public boolean rolled() { return !traits.isEmpty(); }

        /**
         * Two traits, never a pair that contradicts itself (a sociable shy folk). A child
         * takes one of them from a parent and gets the other of its own.
         */
        public void roll(RandomSource r, @Nullable Life mother, @Nullable Life father) {
            traits.clear();
            Life from = mother != null && father != null ? (r.nextBoolean() ? mother : father)
                : mother != null ? mother : father;
            if (from != null && !from.traits.isEmpty()) {
                traits.add(from.traits.get(r.nextInt(from.traits.size())));
            }
            Trait[] all = Trait.values();
            while (traits.size() < 2) {
                Trait t = all[r.nextInt(all.length)];
                if (traits.contains(t)) continue;
                if (!traits.isEmpty() && traits.get(0).opposite() == t) continue;
                traits.add(t);
            }
        }

        public int affinity(UUID other) {
            Bond b = bonds.get(other);
            return b == null ? 0 : b.affinity;
        }

        /** Warm to (or cool on) somebody. */
        public void feel(UUID other, String name, int delta) {
            Bond b = bonds.computeIfAbsent(other, k -> new Bond(name, 0));
            b.name = name;
            b.affinity = Math.max(-100, Math.min(100, b.affinity + delta));
            if (bonds.size() > 16) forgetOne();
        }

        /** Room for somebody new: let go of whoever matters least. */
        private void forgetOne() {
            UUID least = null;
            int weakest = Integer.MAX_VALUE;
            for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
                if (e.getKey().equals(partner)) continue;
                int w = Math.abs(e.getValue().affinity);
                if (w < weakest) { weakest = w; least = e.getKey(); }
            }
            if (least != null) bonds.remove(least);
        }

        /**
         * Friendships want keeping up. Once a day every feeling drifts a little back
         * toward nothing; a partner's does not fall below close.
         */
        public void drift() {
            bonds.entrySet().removeIf(e -> {
                Bond b = e.getValue();
                if (e.getKey().equals(partner) && b.affinity <= CLOSE) return false;
                if (b.affinity > 0) b.affinity -= Math.min(b.affinity, 3);
                else if (b.affinity < 0) b.affinity += Math.min(-b.affinity, 3);
                return b.affinity == 0;
            });
        }

        /** Partners: written on both sides, and as close as two folk get. */
        public void partnerWith(UUID other, String name) {
            partner = other;
            partnerName = name;
            Bond b = bonds.computeIfAbsent(other, k -> new Bond(name, 0));
            b.name = name;
            b.affinity = 100;
        }

        /** Its partner died: free to find somebody again one day (the memory stays). */
        public void widowed() {
            partner = null;
            partnerName = "";
        }

        /** Two whose natures rub: one of them has a trait that is the other's opposite. */
        public boolean clashesWith(Life other) {
            for (Trait t : traits) if (other.has(t.opposite())) return true;
            return false;
        }

        public void setParents(String mother, String father) {
            parents = mother + " and " + father;
        }

        public void hadAChild() { children++; }

        /** Friends, warmest first (the partner is counted separately). */
        public List<Bond> friends() {
            List<Bond> out = new ArrayList<>();
            for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
                if (e.getKey().equals(partner)) continue;
                if (e.getValue().affinity >= FRIEND) out.add(e.getValue());
            }
            out.sort(Comparator.comparingInt((Bond b) -> -b.affinity));
            return out;
        }

        public List<Bond> rivals() {
            List<Bond> out = new ArrayList<>();
            for (Bond b : bonds.values()) if (b.affinity <= RIVAL) out.add(b);
            out.sort(Comparator.comparingInt((Bond b) -> b.affinity));
            return out;
        }

        /** The friend this folk most wants to spend its time with, or null. */
        @Nullable
        public UUID bestFriend() {
            UUID best = null;
            int warmest = FRIEND - 1;
            for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
                if (e.getKey().equals(partner)) continue;
                if (e.getValue().affinity > warmest) { warmest = e.getValue().affinity; best = e.getKey(); }
            }
            return best;
        }

        @Nullable
        public UUID worstRival() {
            UUID worst = null;
            int coldest = RIVAL + 1;
            for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
                if (e.getValue().affinity < coldest) { coldest = e.getValue().affinity; worst = e.getKey(); }
            }
            return worst;
        }

        /** "Cheerful and generous". */
        public String traitsLabel() {
            if (traits.isEmpty()) return "";
            if (traits.size() == 1) return traits.get(0).title();
            return traits.get(0).title() + " and " + traits.get(1).label;
        }

        /** One line about this folk for somebody reading /village people. */
        public String describe(String name, String trade) {
            StringBuilder sb = new StringBuilder(name);
            sb.append(" — ").append(trade.toLowerCase(Locale.ROOT));
            if (!traits.isEmpty()) sb.append(", ").append(traitsLabel().toLowerCase(Locale.ROOT));
            if (partner != null) sb.append(". Partner: ").append(partnerName);
            List<Bond> f = friends();
            if (!f.isEmpty()) {
                sb.append(". Friends: ");
                for (int i = 0; i < Math.min(4, f.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(f.get(i).name).append(f.get(i).affinity >= CLOSE ? " (close)" : "");
                }
            }
            List<Bond> r = rivals();
            if (!r.isEmpty()) {
                sb.append(". Does not get on with ");
                for (int i = 0; i < Math.min(2, r.size()); i++) {
                    if (i > 0) sb.append(" or ");
                    sb.append(r.get(i).name);
                }
            }
            if (!parents.isEmpty()) sb.append(". Child of ").append(parents);
            if (children > 0) sb.append(". ").append(children).append(children == 1 ? " child" : " children");
            return sb.append('.').toString();
        }

        /** What the folk's own screen shows: traits|partner|friends|rivals|family. */
        public String clientLine() {
            StringBuilder friends = new StringBuilder();
            List<Bond> f = friends();
            for (int i = 0; i < Math.min(4, f.size()); i++) {
                if (i > 0) friends.append(", ");
                friends.append(f.get(i).name).append(f.get(i).affinity >= CLOSE ? " (close)" : "");
            }
            StringBuilder rivals = new StringBuilder();
            List<Bond> r = rivals();
            for (int i = 0; i < Math.min(2, r.size()); i++) {
                if (i > 0) rivals.append(", ");
                rivals.append(r.get(i).name);
            }
            String family = (parents.isEmpty() ? "a founder" : "child of " + parents)
                + (children > 0 ? ", " + children + (children == 1 ? " child" : " children") : "");
            return traitsLabel() + "|" + (partner == null ? "" : partnerName) + "|" + friends + "|" + rivals + "|" + family;
        }

        public void save(CompoundTag tag) {
            ListTag t = new ListTag();
            for (Trait tr : traits) t.add(net.minecraft.nbt.StringTag.valueOf(tr.name()));
            tag.put("Traits", t);
            ListTag b = new ListTag();
            for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Id", e.getKey());
                one.putString("Name", e.getValue().name);
                one.putInt("Affinity", e.getValue().affinity);
                b.add(one);
            }
            tag.put("Bonds", b);
            if (partner != null) {
                tag.putUUID("Partner", partner);
                tag.putString("PartnerName", partnerName);
            }
            tag.putString("Parents", parents);
            tag.putInt("Children", children);
        }

        public void load(CompoundTag tag) {
            traits.clear();
            for (Tag t : tag.getList("Traits", Tag.TAG_STRING)) {
                try {
                    Trait tr = Trait.valueOf(t.getAsString());
                    if (!traits.contains(tr)) traits.add(tr);
                } catch (IllegalArgumentException ignored) { }
            }
            bonds.clear();
            for (Tag t : tag.getList("Bonds", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) t;
                if (!one.hasUUID("Id")) continue;
                bonds.put(one.getUUID("Id"), new Bond(one.getString("Name"), one.getInt("Affinity")));
            }
            partner = tag.hasUUID("Partner") ? tag.getUUID("Partner") : null;
            partnerName = tag.getString("PartnerName");
            parents = tag.getString("Parents");
            children = tag.getInt("Children");
        }
    }

    /**
     * How much warmer folk A grows toward folk B for a spell in each other's company:
     * more for the same trade, more off work than at it, more for a cheerful one; the
     * sociable warm faster and the shy slower; and a grump sometimes takes against
     * people, two grumps most of all.
     */
    public static int warmth(Life a, Life b, boolean sameTrade, boolean offWork, RandomSource r) {
        if (a.has(Trait.GRUMPY) && b.has(Trait.GRUMPY) && r.nextInt(3) == 0) return -4;
        if (a.has(Trait.GRUMPY) && r.nextInt(6) == 0) return -3;
        // Now and then two natures just rub each other up the wrong way: the tidy and the
        // easygoing, the chatterbox and the quiet one. Not often — most such pairs get on —
        // but over the weeks one or two of them in a village fall out properly.
        if (a.clashesWith(b) && r.nextInt(14) == 0) return -4;
        int gain = 1 + (sameTrade ? 1 : 0) + (offWork ? 1 : 0) + (b.has(Trait.CHEERFUL) ? 1 : 0);
        if (a.has(Trait.SOCIABLE)) gain += 1 + gain / 2;
        if (a.has(Trait.SHY)) gain = Math.max(1, gain * 2 / 3);
        if (a.has(Trait.GRUMPY)) gain = Math.max(0, gain - 1);
        return gain;
    }
}
