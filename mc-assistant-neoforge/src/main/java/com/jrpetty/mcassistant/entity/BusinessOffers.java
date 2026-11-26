package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] Buying a business, or a share in one, is an offer the owners answer: never one click.
 *
 * <ul>
 * <li><b>The offer.</b> The whole business or a share (a tenth, a quarter, a half), a price, and terms: the staff kept on
 *     (none let go for a fortnight), the name kept, the old owner staying as manager or leaving. The buyer chooses how long it
 *     stands (a day, three, a week). The price goes into escrow at the hall at once (the coin census counts it) and comes back
 *     if the offer is refused, withdrawn or lapses. The valuation guide beside it is honest: book value (its stock, till and
 *     kit, less what it owes), its last four weeks' profit, a goodwill of eight weeks' profit, and the fair value they add up
 *     to, with the plain warning that owners ask more.</li>
 * <li><b>Player owners</b> hear of it (a toast if about, the planner, the Offers tab of their business and its Core) and
 *     accept, refuse or counter. Back and forth as long as both like. With partners, the owners of more than half decide;
 *     each partner's yes or no is shown.</li>
 * <li><b>Folk owners</b> answer after half a day to a day, by their character and their position. Each has a private price,
 *     comfortably over fair value: one and a half to two and a half times it for a healthy firm, more for a thriving one, a
 *     long-held one, a family firm, an ambitious or a close-fisted owner; less only for a struggling firm, an old owner with
 *     no heir, or one in debt. Its respect for the buyer moves it a tenth either way. At or over it: accepted. Near it: a
 *     counter close to it. Well under: refused, with a hint in its own words; a lowball (under half the fair value) offends,
 *     costs a little of its regard, and a second gets the buyer shown the door for a week. A beloved family firm is not for
 *     sale at any sensible price, and its owner says so. The town's council, owning one, takes a fifth over fair value.</li>
 * <li><b>On acceptance</b> the escrow is paid to the owners by their stakes, the stakes pass (whole, or a share from each
 *     owner in proportion), its Core, premises (WP-C's seam: Premises.transferWithFirm) and staff go with it, the terms are
 *     kept, the town hears of it and the timeline records it. Rival offers for the whole are refused and refunded.</li>
 * <li><b>Folk buyers</b> with the means make offers for player businesses through the same flow (listed ones mostly; now and
 *     then a thriving one that isn't), their coin held in escrow the same way.</li>
 * <li>No private purchase where the state owns the firms (Government.allowsPrivateFirms).</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class BusinessOffers extends SavedData {

    private static final String ID = "mc_assistant_wpb_offers";
    @Nullable private static BusinessOffers loose;
    public static final int[] SHARES = { Business.WHOLE, 5000, 2500, 1000 };
    static final int[] DAYS = { 1, 3, 7 };

    public enum State { OPEN, COUNTERED, ACCEPTED, REFUSED, WITHDRAWN, LAPSED }

    public static final class Offer {
        public int id;
        public UUID village = new UUID(0, 0), buyer = new UUID(0, 0);
        public String firm = "", firmName = "", buyerName = "";
        public boolean folkBuyer, keepStaff = true, keepName = true, ownerStays;
        public int parts = Business.WHOLE, price, escrow, counter;
        public long made, expires, decideAt;
        public State state = State.OPEN;
        public String reason = "";
        /** Player owners' answers: true yes, false no. */
        public final Map<UUID, Boolean> votes = new LinkedHashMap<>();
        public final List<String> log = new ArrayList<>();

        public boolean live() {
            return state == State.OPEN || state == State.COUNTERED;
        }

        String what() {
            return parts >= Business.WHOLE ? firmName : parts * 100 / Business.WHOLE + "% of " + firmName;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("v", village);
            t.putUUID("b", buyer);
            t.putString("f", firm);
            t.putString("fn", firmName);
            t.putString("bn", buyerName);
            t.putBoolean("fb", folkBuyer);
            t.putBoolean("ks", keepStaff);
            t.putBoolean("kn", keepName);
            t.putBoolean("os", ownerStays);
            t.putInt("parts", parts);
            t.putInt("price", price);
            t.putInt("escrow", escrow);
            t.putInt("counter", counter);
            t.putLong("made", made);
            t.putLong("exp", expires);
            t.putLong("dec", decideAt);
            t.putString("st", state.name());
            t.putString("why", reason);
            CompoundTag vt = new CompoundTag();
            votes.forEach((u, y) -> vt.putBoolean(u.toString(), y));
            t.put("votes", vt);
            ListTag l = new ListTag();
            for (String s : log) l.add(StringTag.valueOf(s));
            t.put("log", l);
            return t;
        }

        static Offer load(CompoundTag t) {
            Offer o = new Offer();
            o.id = t.getInt("id");
            o.village = t.getUUID("v");
            o.buyer = t.getUUID("b");
            o.firm = t.getString("f");
            o.firmName = t.getString("fn");
            o.buyerName = t.getString("bn");
            o.folkBuyer = t.getBoolean("fb");
            o.keepStaff = t.getBoolean("ks");
            o.keepName = t.getBoolean("kn");
            o.ownerStays = t.getBoolean("os");
            o.parts = t.getInt("parts");
            o.price = t.getInt("price");
            o.escrow = t.getInt("escrow");
            o.counter = t.getInt("counter");
            o.made = t.getLong("made");
            o.expires = t.getLong("exp");
            o.decideAt = t.getLong("dec");
            try { o.state = State.valueOf(t.getString("st")); } catch (IllegalArgumentException e) { o.state = State.LAPSED; }
            o.reason = t.getString("why");
            CompoundTag vt = t.getCompound("votes");
            for (String k : vt.getAllKeys()) o.votes.put(UUID.fromString(k), vt.getBoolean(k));
            ListTag l = t.getList("log", Tag.TAG_STRING);
            for (int i = 0; i < l.size(); i++) o.log.add(l.getString(i));
            return o;
        }
    }

    final Map<Integer, Offer> offers = new LinkedHashMap<>();
    int next = 1;
    /** Coin owed back to a folk not loaded when it was due ("village|folk" to coins): held, and counted, till it is. */
    final Map<String, Integer> folkOwed = new LinkedHashMap<>();

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
        ListTag l = new ListTag();
        for (Offer o : offers.values()) l.add(o.save());
        t.put("offers", l);
        t.putInt("next", next);
        CompoundTag fo = new CompoundTag();
        folkOwed.forEach(fo::putInt);
        t.put("folkOwed", fo);
        return t;
    }

    static BusinessOffers load(CompoundTag t, HolderLookup.Provider p) {
        BusinessOffers d = new BusinessOffers();
        ListTag l = t.getList("offers", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) { Offer o = Offer.load(l.getCompound(i)); d.offers.put(o.id, o); }
        d.next = Math.max(1, t.getInt("next"));
        CompoundTag fo = t.getCompound("folkOwed");
        for (String k : fo.getAllKeys()) d.folkOwed.put(k, fo.getInt(k));
        return d;
    }

    static BusinessOffers of() {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (s == null) {
            if (loose == null) loose = new BusinessOffers();
            return loose;
        }
        return s.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(BusinessOffers::new, BusinessOffers::load, null), ID);
    }

    static void changed() {
        of().setDirty();
    }

    public static void resetForTests() {
        of().offers.clear();
        of().folkOwed.clear();
        changed();
    }

    /** What the hall holds in escrow for offers in this town, coins (the coin census counts it). */
    public static long escrowed(UUID village) {
        long n = 0;
        for (Offer o : of().offers.values()) if (o.village.equals(village) && o.live()) n += o.escrow;
        for (Map.Entry<String, Integer> e : of().folkOwed.entrySet()) if (e.getKey().startsWith(village + "|")) n += e.getValue();
        return n;
    }

    @Nullable
    static Offer get(int id) {
        return of().offers.get(id);
    }

    static List<Offer> forFirm(UUID village, String firm) {
        List<Offer> out = new ArrayList<>();
        for (Offer o : of().offers.values()) if (o.village.equals(village) && o.firm.equals(firm) && o.live()) out.add(o);
        return out;
    }

    static List<Offer> byBuyer(UUID buyer) {
        List<Offer> out = new ArrayList<>();
        for (Offer o : of().offers.values()) if (o.buyer.equals(buyer) && (o.live() || o.made >= 0 && !o.log.isEmpty() && o.state != State.WITHDRAWN)) out.add(o);
        out.removeIf(o -> !o.live() && out.size() > 6);
        return out;
    }

    // ------------------------------------------------------------------ what it's worth

    /** The valuation guide: book value, the last four weeks' profit, goodwill (eight weeks' profit), fair value (pennies). */
    public record Valuation(long book, long profit28, long goodwill, long fair) {}

    public static Valuation value(ServerLevel level, UUID village, Business b) {
        long book = Math.max(0, Enterprise.isFirm(b) ? Enterprise.equity(village, b) : Businesses.worth(level, village, b));
        long p28 = b.last(Businesses.today(level), 28).net();
        long goodwill = Math.max(0, p28 * 2);
        return new Valuation(book, p28, goodwill, Math.max(500, book + goodwill));
    }

    // ------------------------------------------------------------------ making one

    /** Why this buyer may not make an offer for it ("" if they may). */
    static String whyNot(ServerLevel level, UUID village, Business b, UUID buyer, int parts) {
        if (b.type.keeping != Business.Keeping.OWN && !Enterprise.isFirm(b)) return "the town keeps it: it isn't for sale";
        if (!Government.allowsPrivateFirms(village)) return "the state owns the businesses here: none is for private sale";
        if (b.ownedBy(buyer) && parts >= Business.WHOLE) return "it's yours already (in part: offer for a share instead)";
        if (b.ownedBy(buyer) && b.owners.size() == 1) return "it's yours already";
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.contains("petition")) return "it's before the court: the receiver sells it, if it comes to that";
        if (r != null && r.getLong("noOffers/" + buyer) > Businesses.today(level)) return "its owner won't hear another offer from you this week";
        for (Offer o : forFirm(village, b.id)) if (o.buyer.equals(buyer)) return "you have an offer on it already (withdraw it first)";
        if (b.owners.isEmpty()) return "it has no owner to sell it";
        return "";
    }

    /** A player's offer: the price into escrow now. What it says. */
    static String make(ServerLevel level, ServerPlayer p, UUID village, Business b, int parts, int price, boolean keepStaff, boolean keepName, boolean ownerStays, int days) {
        if (!Citizens.is(village, p.getUUID()) && !Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "!The town's owners sell to its citizens and friends.";
        }
        String no = whyNot(level, village, b, p.getUUID(), parts);
        if (!no.isEmpty()) return "!Not possible: " + no + ".";
        if (price < 1) return "!Name a price.";
        if (!Contracts.take(p, village, price, "an offer for " + b.name)) return "!The offer puts " + price + "c in escrow; you carry " + Market.coinsHeld(p) + "c.";
        Offer o = open(level, village, b, p.getUUID(), p.getGameProfile().getName(), false, parts, price, keepStaff, keepName, ownerStays, days);
        return "Your offer of " + price + "c for " + o.what() + " is with " + b.ownersWords() + ". The " + price + "c is held in escrow at the hall till they answer"
            + (folkOrTown(b) ? " (folk owners take half a day to a day to think it over)" : "") + "; withdraw it any time before.";
    }

    static boolean folkOrTown(Business b) {
        for (Business.Stake s : b.owners) if (s.holder() != Business.Holder.PLAYER) return true;
        return false;
    }

    static Offer open(ServerLevel level, UUID village, Business b, UUID buyer, String buyerName, boolean folkBuyer, int parts, int price,
                      boolean keepStaff, boolean keepName, boolean ownerStays, int days) {
        BusinessOffers d = of();
        Offer o = new Offer();
        o.id = d.next++;
        o.village = village;
        o.firm = b.id;
        o.firmName = b.name;
        o.buyer = buyer;
        o.buyerName = buyerName;
        o.folkBuyer = folkBuyer;
        o.parts = parts;
        o.price = price;
        o.escrow = price;
        o.keepStaff = keepStaff;
        o.keepName = keepName;
        o.ownerStays = ownerStays;
        long day = Businesses.today(level);
        o.made = day;
        o.expires = day + Math.max(1, days);
        o.decideAt = folkOrTown(b) ? level.getGameTime() + thinking(o) : 0;
        o.log.add("Day " + (day + 1) + ": " + buyerName + " offered " + price + "c for " + o.what() + terms(o) + ".");
        d.offers.put(o.id, o);
        changed();
        b.note(day, buyerName + " offered " + price + "c for " + o.what().replace(b.name, "it"));
        tellOwners(level, village, b, buyerName + " offers " + price + "c for " + o.what() + ". Answer it on the Offers tab (its Business Core, or Your businesses).");
        return o;
    }

    /** Half a day to a day, as the folk take to think it over. */
    static long thinking(Offer o) {
        return 12000L + Math.floorMod(o.id * 7919L + o.buyer.hashCode(), 12000L);
    }

    static String terms(Offer o) {
        List<String> t = new ArrayList<>();
        if (o.keepStaff) t.add("the staff kept on");
        if (o.keepName) t.add("the name kept");
        else t.add("a new name");
        if (o.parts >= Business.WHOLE) t.add(o.ownerStays ? "the owner staying as manager" : "the owner leaving");
        return t.isEmpty() ? "" : " (" + String.join(", ", t) + ")";
    }

    static void tellOwners(ServerLevel level, UUID village, Business b, String text) {
        for (Business.Stake s : b.owners) {
            if (s.holder() != Business.Holder.PLAYER || s.who() == null) continue;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.who());
            if (p == null) continue;
            WorkLife.toast(p, text);
            p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
            Planner.add(p, Businesses.today(level), BusinessCore.hourOf(level.getDayTime()), "offer", text, BusinessCore.siteOf(level, Villages.get(village), b));
        }
    }

    static void tellBuyer(ServerLevel level, Offer o, String text) {
        if (o.folkBuyer) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(o.buyer);
        if (p == null) return;
        WorkLife.toast(p, text);
        p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
        Planner.add(p, Businesses.today(level), BusinessCore.hourOf(level.getDayTime()), "offer", text, null);
    }

    // ------------------------------------------------------------------ the owners' answers

    /** A player owner's yes. The deal is done once the owners of more than half have said yes. */
    static String accept(ServerLevel level, ServerPlayer owner, Offer o) {
        Business b = Businesses.of(o.village, o.firm);
        if (b == null || !o.live()) return "!That offer has gone.";
        if (!b.ownedBy(owner.getUUID())) return "!It isn't yours to sell.";
        if (o.state == State.COUNTERED) return "!You've countered: it's with " + o.buyerName + " now.";
        o.votes.put(owner.getUUID(), true);
        o.log.add("Day " + (Businesses.today(level) + 1) + ": " + owner.getGameProfile().getName() + " said yes.");
        changed();
        if (ownersYes(b, o)) return complete(level, o, b);
        return "Your yes is counted. It needs the owners of more than half: " + yesParts(b, o) * 100 / Business.WHOLE + "% so far.";
    }

    static String refuse(ServerLevel level, ServerPlayer owner, Offer o) {
        Business b = Businesses.of(o.village, o.firm);
        if (b == null || !o.live()) return "!That offer has gone.";
        if (!b.ownedBy(owner.getUUID())) return "!It isn't yours to answer.";
        o.votes.put(owner.getUUID(), false);
        o.log.add("Day " + (Businesses.today(level) + 1) + ": " + owner.getGameProfile().getName() + " said no.");
        if (noParts(b, o) * 2 >= Business.WHOLE) {
            close(level, o, State.REFUSED, owner.getGameProfile().getName() + " refused it");
            tellBuyer(level, o, b.name + ": your offer of " + o.price + "c was refused. Your coin is back.");
            return "Refused. " + o.buyerName + "'s " + o.price + "c goes back to them.";
        }
        changed();
        return "Your no is counted.";
    }

    /** An owner's (or the buyer's) counter: a new price. Back and forth till one side accepts or walks away. */
    static String counter(ServerLevel level, ServerPlayer who, Offer o, int price) {
        Business b = Businesses.of(o.village, o.firm);
        if (b == null || !o.live()) return "!That offer has gone.";
        long day = Businesses.today(level);
        if (who.getUUID().equals(o.buyer)) {
            if (o.state != State.COUNTERED) return "!It's with the owners: wait for their answer, or withdraw it.";
            int more = price - o.escrow;
            if (more > 0 && !Contracts.take(who, o.village, more, "a raised offer for " + b.name)) return "!That puts " + more + "c more in escrow; you carry " + Market.coinsHeld(who) + "c.";
            if (more < 0) Contracts.give(level, o.buyer, o.village, -more, "an offer lowered");
            o.escrow = price;
            o.price = price;
            o.state = State.OPEN;
            o.votes.clear();
            o.decideAt = folkOrTown(b) ? level.getGameTime() + thinking(o) / 2 : 0;
            o.log.add("Day " + (day + 1) + ": " + o.buyerName + " came back with " + price + "c.");
            changed();
            tellOwners(level, o.village, b, o.buyerName + " comes back with " + price + "c for " + o.what() + ".");
            return "Your " + price + "c is with " + b.ownersWords() + ".";
        }
        if (!Partners.canDecide(o.village, b, who.getUUID())) return "!" + Partners.whyNot(o.village, b);
        o.state = State.COUNTERED;
        o.counter = price;
        o.reason = "";
        o.log.add("Day " + (day + 1) + ": " + who.getGameProfile().getName() + " countered at " + price + "c.");
        changed();
        tellBuyer(level, o, b.name + ": the owner counters your offer at " + price + "c. Answer it on Your businesses, Offers you've made.");
        return "You've countered at " + price + "c. It's with " + o.buyerName + " now.";
    }

    /** The buyer takes the owners' counter: the difference into (or out of) escrow, and the deal is done. */
    static String takeCounter(ServerLevel level, ServerPlayer buyer, Offer o) {
        Business b = Businesses.of(o.village, o.firm);
        if (b == null || o.state != State.COUNTERED || !buyer.getUUID().equals(o.buyer)) return "!There's no counter to take.";
        int more = o.counter - o.escrow;
        if (more > 0 && !Contracts.take(buyer, o.village, more, "a counter-offer taken for " + b.name)) return "!Taking it puts " + more + "c more in escrow; you carry " + Market.coinsHeld(buyer) + "c.";
        if (more < 0) Contracts.give(level, o.buyer, o.village, -more, "an offer lowered");
        o.escrow = o.counter;
        o.price = o.counter;
        o.log.add("Day " + (Businesses.today(level) + 1) + ": " + o.buyerName + " took the counter.");
        return complete(level, o, b);
    }

    static String withdraw(ServerLevel level, ServerPlayer buyer, Offer o) {
        if (!o.live() || !buyer.getUUID().equals(o.buyer)) return "!There's nothing to withdraw.";
        close(level, o, State.WITHDRAWN, o.buyerName + " withdrew it");
        Business b = Businesses.of(o.village, o.firm);
        if (b != null) tellOwners(level, o.village, b, o.buyerName + " withdrew their offer for " + o.what() + ".");
        return "Withdrawn. Your " + o.price + "c is back.";
    }

    /** Ended without a sale: the escrow back to the buyer. */
    static void close(ServerLevel level, Offer o, State st, String why) {
        refund(level, o);
        o.state = st;
        o.reason = why;
        o.log.add("Day " + (Businesses.today(level) + 1) + ": " + why + ".");
        changed();
    }

    static void refund(ServerLevel level, Offer o) {
        if (o.escrow <= 0) return;
        if (o.folkBuyer) {
            VillageFolkEntity f = Homes.loaded(o.village, o.buyer);
            if (f != null) f.earn(o.escrow);
            else of().folkOwed.merge(o.village + "|" + o.buyer, o.escrow, Integer::sum);
        } else Contracts.give(level, o.buyer, o.village, o.escrow, "an offer's escrow returned");
        o.escrow = 0;
    }

    static long yesParts(Business b, Offer o) {
        long n = 0;
        for (Business.Stake s : b.owners) if (s.who() != null && Boolean.TRUE.equals(o.votes.get(s.who()))) n += s.parts();
        return n;
    }

    static long noParts(Business b, Offer o) {
        long n = 0;
        for (Business.Stake s : b.owners) if (s.who() != null && Boolean.FALSE.equals(o.votes.get(s.who()))) n += s.parts();
        return n;
    }

    // ------------------------------------------------------------------ the folk owners' answers

    static final String[] ACCEPT = { "%dc. Fair enough. It's yours; look after it.", "Done. I'll want it in coin, mind. Oh, it is.",
        "That'll do. Better you than someone who'd ruin it.", "%dc. I'd be a fool to say no, and I'm not.", "Right. Shake on it before I change my mind.",
        "All right. I was ready for a rest anyway." };
    static final String[] NEAR = { "Close. Make it %dc and we'll talk.", "Not quite. %dc and it's yours.", "You're nearly there. %dc.",
        "I'd want %dc. Not a coin under.", "Make it worth my while: %dc.", "Round it up to %dc and I'll think kindly of you." };
    static final String[] SHORT = { "Not for twice that.", "Make it worth my while.", "You'll have to do a lot better than that.",
        "It's worth more to me than that, and to you, if you're honest.", "I've had better offers from the weather.", "No. Come back with a real number." };
    static final String[] LOWBALL = { "%dc for my %s? I've got stock worth more than that.", "Is that a joke? It isn't a good one.",
        "%dc. You've a nerve, I'll give you that.", "I'll pretend I didn't hear that.", "For %dc you can buy a loaf from me. That's all.",
        "Get out of my %s." };
    static final String[] NEVER = { "It's not for sale. It was my mother's and it'll be my children's.", "Not at any price you'd pay. Family, you see.",
        "No. Some things you don't sell.", "My family built this. It stays in the family.", "Keep your coin. This place isn't a thing to sell.",
        "Ask me again and I'll take it badly. It's not for sale." };
    static final String[] TOWN = { "The council will take %dc, and not a coin less.", "The council's terms: %dc.", "For the town's books, %dc.",
        "The council looked at it. %dc, if you want it.", "%dc, the council says. It answers to the town.", "Agreed, at %dc: the council's word." };

    static String line(String[] lines, long seed, int coins, String noun) {
        String s = lines[(int) Math.floorMod(seed, (long) lines.length)];
        if (s.contains("%d") && s.contains("%s")) return String.format(Locale.ROOT, s, coins, noun);
        if (s.contains("%d")) return String.format(Locale.ROOT, s, coins);
        if (s.contains("%s")) return String.format(Locale.ROOT, s, noun);
        return s;
    }

    /** A folk owner's private price for the whole business, coins (Long.MAX_VALUE: not for sale). */
    static long reservation(ServerLevel level, UUID village, Business b, Business.Stake s, UUID buyer) {
        Valuation v = value(level, village, b);
        long fair = v.fair();
        VillageFolkEntity f = s.who() == null ? null : Homes.loaded(village, s.who());
        double k = 1.5 + Math.floorMod((b.id + s.who()).hashCode(), 101) / 100.0;   // 1.5 .. 2.5
        long day = Businesses.today(level);
        long held = day - b.opened;
        CompoundTag r = Enterprise.rec(village, b.id);
        boolean struggling = held >= 14 && v.profit28() < 0 || r != null && (r.contains("petition") || r.getInt("broke") > 0) || b.lossDays > 7;
        boolean thriving = !struggling && v.profit28() * 4 > v.book();
        EnumSet<Temperament.Mark> m = f == null ? EnumSet.noneOf(Temperament.Mark.class) : Deputy.marksOf(f);
        boolean family = f != null && !Lineage.children(f).isEmpty() && held > 30;
        if (family && held > 60 && (FAMILY_FOR_TESTS || Math.floorMod((b.id + "never").hashCode(), 3) == 0)) return Long.MAX_VALUE;
        if (thriving) k += 0.4;
        if (held > 60) k += 0.3;
        if (family) k += 0.5;
        if (m.contains(Temperament.Mark.AMBITIOUS)) k += thriving ? 0.8 : 0.3;
        if (m.contains(Temperament.Mark.THRIFTY) || m.contains(Temperament.Mark.SHREWD)) k += 0.25;
        if (struggling) k = 0.9 + (k - 1.5) * 0.25;
        if (f != null && f.isOld() && Lineage.heir(f, f.stationTask()) == null) k *= 0.8;
        if (f != null && f.purse() < 3 && struggling) k *= 0.85;
        int regard = f == null ? 0 : f.persona().affinity(buyer);
        k *= 1 - Math.max(-100, Math.min(100, regard)) / 1000.0;
        return Math.max(1, Math.round(fair * k / 100.0));
    }

    static boolean FAMILY_FOR_TESTS;

    /** The folk and the town owners answer, when they've had time to think. */
    static void decide(ServerLevel level, Offer o) {
        Business b = Businesses.of(o.village, o.firm);
        if (b == null || !b.open) { close(level, o, State.LAPSED, "the business is gone"); return; }
        long day = Businesses.today(level);
        CompoundTag r = Enterprise.rec(o.village, b.id);
        double share = o.parts / (double) Business.WHOLE;
        String noun = BusinessCore.noun(o.village, b);
        boolean anyNo = false;
        int counterAt = 0;
        String said = "";
        for (Business.Stake s : b.owners) {
            if (s.holder() == Business.Holder.PLAYER || s.who() == null && s.holder() == Business.Holder.FOLK) continue;
            UUID key = s.who() == null ? new UUID(0, 0) : s.who();
            if (o.votes.containsKey(key)) continue;
            long seed = o.id * 31L + o.price;
            if (s.holder() == Business.Holder.TOWN) {
                long want = Math.round(value(level, o.village, b).fair() * 1.2 * share / 100.0);
                if (o.price >= want) { o.votes.put(key, true); said = "The council: \"" + line(TOWN, seed, (int) want, noun) + "\""; }
                else { anyNo = true; counterAt = Math.max(counterAt, (int) want); said = "The council: \"" + line(TOWN, seed, (int) want, noun) + "\""; }
                continue;
            }
            long res = reservation(level, o.village, b, s, o.buyer);
            VillageFolkEntity f = Homes.loaded(o.village, s.who());
            String who = s.name();
            if (res == Long.MAX_VALUE) {
                anyNo = true;
                counterAt = -1;
                said = who + ": \"" + line(NEVER, seed, 0, noun) + "\"";
                break;
            }
            long want = Math.max(1, Math.round(res * share));
            long fairCoins = Math.max(1, Math.round(value(level, o.village, b).fair() * share / 100.0));
            if (o.price >= want) {
                o.votes.put(key, true);
                said = who + ": \"" + line(ACCEPT, seed, o.price, noun) + "\"";
            } else if (o.price * 2 < fairCoins) {
                anyNo = true;
                counterAt = -1;
                said = who + ": \"" + line(LOWBALL, seed, o.price, noun) + "\"";
                if (f != null) f.persona().feelFor(o.buyer, o.buyerName, -3);
                if (r != null) {
                    int n = r.getInt("lowball/" + o.buyer) + 1;
                    r.putInt("lowball/" + o.buyer, n);
                    if (n >= 2) { r.putLong("noOffers/" + o.buyer, day + 7); said += " (" + who + " won't hear another offer from " + o.buyerName + " for a week.)"; }
                    Accounts.dirty();
                }
            } else if (o.price * 10 >= want * 8) {
                anyNo = true;
                int near = (int) Math.round(want * 1.04);
                counterAt = counterAt < 0 ? -1 : Math.max(counterAt, near);
                said = who + ": \"" + line(NEAR, seed, near, noun) + "\"";
            } else {
                anyNo = true;
                counterAt = -1;
                said = who + ": \"" + line(SHORT, seed, 0, noun) + "\"";
            }
        }
        o.decideAt = 0;
        o.reason = said;
        if (!said.isEmpty()) o.log.add("Day " + (day + 1) + ": " + said);
        if (!anyNo) {
            if (ownersYes(b, o)) { complete(level, o, b); return; }
            changed();
            return;
        }
        if (counterAt > 0) {
            o.state = State.COUNTERED;
            o.counter = counterAt;
            changed();
            tellBuyer(level, o, b.name + ": " + said + " (a counter at " + counterAt + "c)");
            return;
        }
        close(level, o, State.REFUSED, "refused");
        o.reason = said;
        tellBuyer(level, o, b.name + ": " + said + " Your " + o.price + "c is back.");
    }

    /** Every folk and town owner has said yes, and the player owners of more than half the rest (or there are none). */
    static boolean ownersYes(Business b, Offer o) {
        long yes = 0, total = 0;
        for (Business.Stake s : b.owners) {
            UUID key = s.who() == null ? new UUID(0, 0) : s.who();
            total += s.parts();
            if (Boolean.TRUE.equals(o.votes.get(key))) yes += s.parts();
        }
        return total > 0 && yes * 2 > total;
    }

    // ------------------------------------------------------------------ the deal

    /** Accepted: the escrow to the owners by their stakes, the stakes to the buyer, the terms kept, the town told. */
    static String complete(ServerLevel level, Offer o, Business b) {
        long day = Businesses.today(level);
        UUID village = o.village;
        CompoundTag r = Enterprise.rec(village, b.id);
        int coins = o.escrow;
        double share = Math.min(1, o.parts / (double) Business.WHOLE);
        List<Business.Stake> old = new ArrayList<>(b.owners);
        long totalParts = 0;
        for (Business.Stake s : old) totalParts += s.parts();
        int handed = 0;
        UUID formerPlayer = null;
        VillageFolkEntity formerFolk = null;
        for (int i = 0; i < old.size(); i++) {
            Business.Stake st = old.get(i);
            int part = i == old.size() - 1 ? coins - handed : (int) (coins * (long) st.parts() / Math.max(1, totalParts));
            handed += part;
            if (st.holder() == Business.Holder.PLAYER && st.who() != null) {
                Contracts.give(level, st.who(), village, part, "sold " + o.what());
                if (formerPlayer == null) formerPlayer = st.who();
            } else if (st.holder() == Business.Holder.FOLK && st.who() != null) {
                VillageFolkEntity f = Homes.loaded(village, st.who());
                if (f != null) { f.earn(part); formerFolk = f; }
                else of().folkOwed.merge(village + "|" + st.who(), part, Integer::sum);
            } else Businesses.toTreasury(village, part * 100L);
        }
        o.escrow = 0;
        // The stakes.
        b.owners.clear();
        if (share >= 1) {
            b.owners.add(new Business.Stake(o.folkBuyer ? Business.Holder.FOLK : Business.Holder.PLAYER, o.buyer, o.buyerName, Business.WHOLE));
        } else {
            int bought = 0;
            for (Business.Stake st : old) {
                int give = (int) Math.round(st.parts() * share);
                bought += give;
                if (st.who() != null && st.who().equals(o.buyer)) { bought -= give; give = 0; }
                if (st.parts() - give > 0) b.owners.add(new Business.Stake(st.holder(), st.who(), st.name(), st.parts() - give));
            }
            int already = 0;
            for (Business.Stake st : old) if (o.buyer.equals(st.who())) already = st.parts();
            b.owners.removeIf(st -> o.buyer.equals(st.who()));
            b.owners.add(new Business.Stake(o.folkBuyer ? Business.Holder.FOLK : Business.Holder.PLAYER, o.buyer, o.buyerName, bought + already));
            int sum = 0;
            for (Business.Stake st : b.owners) sum += st.parts();
            if (sum != Business.WHOLE && !b.owners.isEmpty()) {           // rounding: the largest stake squares it
                Business.Stake big = b.owners.get(0);
                for (Business.Stake st : b.owners) if (st.parts() > big.parts()) big = st;
                b.owners.set(b.owners.indexOf(big), new Business.Stake(big.holder(), big.who(), big.name(), big.parts() + Business.WHOLE - sum));
            }
        }
        if (r != null && share >= 1) {
            if (o.folkBuyer) r.putUUID("founder", o.buyer);
            else r.remove("founder");
            r.remove("sale");
            r.remove("managing");
            if (o.keepStaff) r.putLong("keepStaffUntil", day + 14);
            if (!o.keepName) {
                BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
                if (t != null) { String first = o.buyerName.split(" ")[0]; b.name = t.nameFor(first); }
            }
            if (o.ownerStays && formerFolk != null) {                     // the old owner stays on, as the deputy
                Enterprise.addStaff(village, b, r, formerFolk);
                Deputy.appoint(level, village, b, formerFolk, "Manager", 0);
            }
        }
        if (share >= 1 && formerPlayer != null) Premises.transferWithFirm(level, village, b, formerPlayer, o.buyer);
        o.state = State.ACCEPTED;
        o.reason = "sold";
        o.log.add("Day " + (day + 1) + ": sold to " + o.buyerName + " for " + coins + "c.");
        // Rival offers for the whole are done with.
        if (share >= 1) for (Offer x : forFirm(village, b.id)) if (x != o) {
            close(level, x, State.REFUSED, "sold to another");
            tellBuyer(level, x, o.firmName + " was sold to another buyer. Your " + x.price + "c is back.");
        }
        String words = o.buyerName + " bought " + o.what() + " for " + coins + "c";
        b.note(day, words.replace(o.firmName, "it"));
        if (r != null) BusinessCore.event(r, day, words);
        Villages.tell(village, level.getDayTime() / 24000L, words);
        Accounts.dirty();
        Businesses.dirty();
        changed();
        tellBuyer(level, o, "Done: " + words + ". " + (share >= 1 ? "It's yours: open its Business Core." : "Your share's profit comes to you by stake."));
        tellOwners(level, village, b, words + ".");
        return "Done: " + words + ", shared among its owners by their stakes.";
    }

    // ------------------------------------------------------------------ folk buyers, and time passing

    /** A folk buyer's offer, its coin out of its purse into escrow. */
    static Offer folkOffer(ServerLevel level, UUID village, Business b, VillageFolkEntity f, int price) {
        if (!whyNot(level, village, b, f.getUUID(), Business.WHOLE).isEmpty() || !f.spend(price)) return null;
        return open(level, village, b, f.getUUID(), f.displayNameCap(), true, Business.WHOLE, price, true, true, false, 3);
    }

    /** A folk buyer's answer to a counter: taken if it isn't a quarter over its offer and its purse runs to it; else it walks. */
    static void folkAnswer(ServerLevel level, Offer o) {
        Business b = Businesses.of(o.village, o.firm);
        VillageFolkEntity f = Homes.loaded(o.village, o.buyer);
        o.decideAt = 0;
        int more = o.counter - o.escrow;
        if (b != null && f != null && o.counter * 4 <= o.price * 5 && (more <= 0 || f.spend(more))) {
            if (more < 0) f.earn(-more);
            o.escrow = o.counter;
            o.price = o.counter;
            o.log.add("Day " + (Businesses.today(level) + 1) + ": " + o.buyerName + " took the counter.");
            complete(level, o, b);
            return;
        }
        close(level, o, State.REFUSED, o.buyerName + " walked away from the counter");
        if (b != null) tellOwners(level, o.village, b, o.buyerName + " won't go to " + o.counter + "c for " + o.what() + ": the offer's off.");
    }

    /** Each morning: offers lapse, folk with means look at the businesses for sale (and now and then one that isn't). */
    static void morning(ServerLevel level, UUID village, long day) {
        for (Offer o : new ArrayList<>(of().offers.values())) {
            if (!o.village.equals(village) || !o.live() || day < o.expires) continue;
            close(level, o, State.LAPSED, "it lapsed unanswered");
            tellBuyer(level, o, o.firmName + ": your offer lapsed. Your " + o.price + "c is back.");
        }
        for (Map.Entry<String, Integer> e : new ArrayList<>(of().folkOwed.entrySet())) {
            if (!e.getKey().startsWith(village + "|")) continue;
            VillageFolkEntity f = Homes.loaded(village, UUID.fromString(e.getKey().substring(e.getKey().indexOf('|') + 1)));
            if (f != null) { f.earn(e.getValue()); of().folkOwed.remove(e.getKey()); changed(); }
        }
        for (Business b : Enterprise.open(village)) {
            boolean player = false;
            for (Business.Stake s : b.owners) player |= s.holder() == Business.Holder.PLAYER;
            if (!player) continue;
            CompoundTag sale = FirmSale.sale(village, b);
            Valuation v = value(level, village, b);
            boolean thriving = v.profit28() * 4 > v.book() && v.profit28() > 0;
            if (sale == null && !(thriving && Math.floorMod(day + b.id.hashCode(), 10) == 0)) continue;
            long target = sale != null ? sale.getLong("ask") : Math.round(v.fair() * 1.2);
            VillageFolkEntity best = null;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || b.ownedBy(f.getUUID()) || !FirmBranches.owned(village, f.getUUID()).isEmpty()) continue;
                if (Enterprise.means(f) * 100L < target * 4 / 5 || Temperament.drive(f) < 0) continue;
                if (best == null || Enterprise.means(f) > Enterprise.means(best)) best = f;
            }
            if (best == null || Math.floorMod(day + best.getUUID().getLeastSignificantBits(), 2L) != 0) continue;
            double k = 0.85 + Math.floorMod(best.getUUID().hashCode() + day, 21) / 100.0;
            int price = (int) Math.min(Enterprise.means(best), Math.round(target * k / 100.0));
            if (price > 0) folkOffer(level, village, b, best, price);
        }
    }

    private static long lastDay = -1;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 != 61) return;
        ServerLevel level = e.getServer().overworld();
        long now = level.getGameTime();
        for (Offer o : new ArrayList<>(of().offers.values())) {
            if (o.live() && o.state == State.OPEN && o.decideAt > 0 && now >= o.decideAt) {
                try { decide(level, o); } catch (RuntimeException ex) { com.mojang.logging.LogUtils.getLogger().warn("[wpb] an offer's answer failed", ex); }
            } else if (o.live() && o.state == State.COUNTERED && o.folkBuyer) {   // a folk buyer thinks the counter over
                if (o.decideAt == 0) { o.decideAt = now + 6000; changed(); }
                else if (now >= o.decideAt) folkAnswer(level, o);
            }
        }
        long day = level.getDayTime() / 24000L;
        if (day == lastDay) return;
        lastDay = day;
        for (Villages.Village v : Villages.every()) {
            try { morning(level, v.id(), Businesses.today(level)); } catch (RuntimeException ex) { com.mojang.logging.LogUtils.getLogger().warn("[wpb] offers' morning failed in {}", v.id(), ex); }
        }
    }

    // ------------------------------------------------------------------ the way in from the old buttons

    /** The old one-click Buy: now the offer page, with the valuation guide. */
    public static String instead(ServerLevel level, UUID village, Business b, ServerPlayer p, int parts) {
        PlayerLife.open(p, "offer", LifePage.args("firm", b.id, "parts", parts));
        return "Businesses are bought by offer, not on the spot: name your price for " + b.name + " on its offer page; its owners answer.";
    }

    // ------------------------------------------------------------------ tests

    public static Offer makeForTests(ServerLevel level, ServerPlayer p, UUID village, Business b, int parts, int price) {
        String said = make(level, p, village, b, parts, price, true, true, false, 3);
        com.mojang.logging.LogUtils.getLogger().info("[wpb] offer: {}", said);
        Offer last = null;
        for (Offer o : of().offers.values()) if (o.buyer.equals(p.getUUID()) && o.firm.equals(b.id)) last = o;
        return last != null && last.live() ? last : null;
    }

    public static Offer folkOfferForTests(ServerLevel level, UUID village, Business b, VillageFolkEntity f, int price) {
        return folkOffer(level, village, b, f, price);
    }

    public static String acceptForTests(ServerLevel level, ServerPlayer owner, Offer o) {
        return accept(level, owner, o);
    }

    public static String refuseForTests(ServerLevel level, ServerPlayer owner, Offer o) {
        return refuse(level, owner, o);
    }

    public static String counterForTests(ServerLevel level, ServerPlayer who, Offer o, int price) {
        return counter(level, who, o, price);
    }

    public static String takeCounterForTests(ServerLevel level, ServerPlayer buyer, Offer o) {
        return takeCounter(level, buyer, o);
    }

    public static String withdrawForTests(ServerLevel level, ServerPlayer buyer, Offer o) {
        return withdraw(level, buyer, o);
    }

    public static void decideForTests(ServerLevel level, Offer o) {
        decide(level, o);
    }

    public static void morningForTests(ServerLevel level, UUID village, long day) {
        morning(level, village, day);
    }

    public static long reservationForTests(ServerLevel level, UUID village, Business b, VillageFolkEntity owner, UUID buyer) {
        for (Business.Stake s : b.owners) if (owner.getUUID().equals(s.who())) return reservation(level, village, b, s, buyer);
        return -1;
    }

    public static void familyForTests(boolean on) {
        FAMILY_FOR_TESTS = on;
    }
}
