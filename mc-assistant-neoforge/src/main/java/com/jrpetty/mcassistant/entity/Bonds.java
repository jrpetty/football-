package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * What two neighbouring villages are to each other, beyond the number (Diplomacy): what they
 * remember of each other, what they have agreed, and who has married whom.
 * <ul>
 * <li><b>Memory.</b> Each village remembers its dealings with each neighbour: the traders who came,
 *     the bread sent in a hungry week, the scuffle at the boundary stone, the tribute paid and
 *     resented. A fresh grudge or a fresh kindness holds a relation where it is — it does not
 *     drift back to nothing while the memory is warm.</li>
 * <li><b>The border.</b> Two villages whose lands overlap quarrel over the ground between them until
 *     their elders walk the line and agree a border. After that the quarrel is over, and neither
 *     stakes a field, a wood or a mine on the other's side of it (nor does any village ever stake
 *     one nearer a neighbour's heart than its own).</li>
 * <li><b>A truce.</b> A feud that cools gets ten days of truce: no brawls, no insults, and the
 *     relation cannot fall back into a feud while it lasts.</li>
 * <li><b>A go-between.</b> Two villages at odds that both get on with a third: its elder brings them
 *     to terms.</li>
 * <li><b>Marriages.</b> Neighbours on good terms marry across the boundary: one of the pair moves
 *     to the other's village and the wedding is held there. Every marriage binds the two villages
 *     closer, for good.</li>
 * <li><b>The feast.</b> On the weekly feast, friends send a guest with a gift.</li>
 * <li><b>A hand when short.</b> Friends send what they can spare (the Budget's spare, never what
 *     they need) of what the other is short of.</li>
 * <li><b>The harvest contest.</b> Once a week neighbours compare what they made: the winner crows,
 *     a good loser takes it well, a prickly elder does not.</li>
 * <li><b>Word of a player spreads.</b> A player honoured in one village is welcomed by its allies and
 *     looked at sideways by its enemies.</li>
 * <li><b>A player can carry a letter</b> from one elder to another (a coin or three for the walk,
 *     and the two villages the warmer for it), and an honoured friend of both can broker a trade
 *     pact between them.</li>
 * </ul>
 */
public final class Bonds {

    private Bonds() {}

    /** How many things a village remembers of each neighbour. */
    static final int MEMORY = 8;
    /** How long a memory stays warm enough to hold a relation where it is. */
    static final int WARM_DAYS = 30;
    /** How long a truce lasts. */
    static final int TRUCE_DAYS = 10;
    private static final String SEP = "~", FIELD = "|";

    // ------------------------------------------------------------------ memory

    public record Memory(long day, int weight, String what) {}

    /** Both villages remember this of each other. */
    public static void remember(UUID x, UUID y, long day, int weight, String what) {
        String clean = what.replace(SEP, "-").replace(FIELD, "/");
        add(x, y, day, weight, clean);
        add(y, x, day, weight, clean);
    }

    private static void add(UUID village, UUID other, long day, int weight, String what) {
        List<Memory> all = new ArrayList<>(memories(village, other));
        all.add(new Memory(day, weight, what));
        while (all.size() > MEMORY) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Memory m : all) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(m.day()).append(FIELD).append(m.weight()).append(FIELD).append(m.what());
        }
        Ledger.note(village, "memory/" + other, sb.toString());
    }

    /** What this village remembers of that one, oldest first. */
    public static List<Memory> memories(UUID village, UUID other) {
        String note = Ledger.note(village, "memory/" + other);
        List<Memory> out = new ArrayList<>();
        if (note == null || note.isEmpty()) return out;
        for (String one : note.split(SEP)) {
            String[] p = one.split("\\" + FIELD, 3);
            if (p.length < 3) continue;
            try {
                out.add(new Memory(Long.parseLong(p[0]), Integer.parseInt(p[1]), p[2]));
            } catch (NumberFormatException ignored) {
                // a line that will not read is forgotten
            }
        }
        return out;
    }

    /** How warmly (or bitterly) the village feels its recent dealings with that one: the month's memories. */
    public static int feeling(UUID village, UUID other, long day) {
        int sum = 0;
        for (Memory m : memories(village, other)) if (day - m.day() <= WARM_DAYS) sum += m.weight();
        return sum;
    }

    // ------------------------------------------------------------------ the border

    public static boolean border(UUID a, UUID b) {
        String s = Ledger.note(a, "border/" + b);
        return s != null && !s.isEmpty();
    }

    /** The elders walk the line between the two hearts and agree it. */
    public static void agreeBorder(UUID a, UUID b, long day) {
        Ledger.note(a, "border/" + b, Long.toString(day));
        Ledger.note(b, "border/" + a, Long.toString(day));
        String line = "the elders of " + Villages.name(a) + " and " + Villages.name(b)
            + " walked the land between them and agreed a border, with stones to mark it";
        Villages.tell(a, day, line);
        Villages.tell(b, day, line);
        remember(a, b, day, 6, "we agreed a border");
    }

    /**
     * Is this spot, with a plot this big round it, over the line toward a neighbour — nearer
     * the neighbour's heart than this village's own? No village stakes its plots there: the
     * ground halfway between two hearts is the border, agreed or not.
     */
    public static boolean overBorder(@Nullable UUID village, BlockPos heart, BlockPos spot, int plotRadius) {
        if (village == null) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        double own = Math.sqrt(spot.distSqr(heart));
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village) || !o.dim().equals(v.dim())) continue;
            if (Diplomacy.apart(v, o) > 2 * Diplomacy.CROWDED) continue;
            double theirs = Math.sqrt(spot.distSqr(o.centre()));
            if (theirs + plotRadius < own) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ truces

    public static boolean truce(UUID a, UUID b, long day) {
        String s = Ledger.note(a, "truce/" + b);
        if (s == null || s.isEmpty()) return false;
        try {
            return Long.parseLong(s) >= day;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static void callTruce(UUID a, UUID b, long day) {
        String until = Long.toString(day + TRUCE_DAYS);
        Ledger.note(a, "truce/" + b, until);
        Ledger.note(b, "truce/" + a, until);
    }

    // ------------------------------------------------------------------ marriages

    /** How many marriages bind the two villages. */
    public static int ties(UUID a, UUID b) {
        String s = Ledger.note(a, "ties/" + b);
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void tie(UUID a, UUID b) {
        int n = ties(a, b) + 1;
        Ledger.note(a, "ties/" + b, Integer.toString(n));
        Ledger.note(b, "ties/" + a, Integer.toString(n));
    }

    /** One grown, unpartnered folk of the village who might marry out (not its elder), or null. */
    @Nullable
    static VillageFolkEntity single(UUID village, Random rng) {
        String elder = Villages.elderName(village);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.life().partner() != null || f.displayNameCap().equals(elder)) continue;
            out.add(f);
        }
        return out.isEmpty() ? null : out.get(rng.nextInt(out.size()));
    }

    /**
     * A wedding across the boundary: one from each village, and the one from the bigger village
     * moves to the smaller (where a pair of hands is missed more). Returns the newlyweds, or null.
     */
    @Nullable
    public static VillageFolkEntity[] marry(ServerLevel level, Villages.Village a, Villages.Village b, long day, Random rng) {
        VillageFolkEntity fa = single(a.id(), rng), fb = single(b.id(), rng);
        if (fa == null || fb == null) return null;
        boolean aMoves = Villages.headcount(a.id()) >= Villages.headcount(b.id());
        VillageFolkEntity mover = aMoves ? fa : fb, stays = aMoves ? fb : fa;
        Villages.Village from = aMoves ? a : b, to = aMoves ? b : a;
        BlockPos home = stays.blockPosition();
        if (!level.isLoaded(home) || !level.isLoaded(mover.blockPosition())) return null;
        mover.life().partnerWith(stays.getUUID(), stays.displayNameCap());
        stays.life().partnerWith(mover.getUUID(), mover.displayNameCap());
        // Off its old plot and into the new village: it takes up whatever trade its new home wants.
        mover.setWorkZone(null);
        mover.joinVillage(to.id(), to.centre());
        mover.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        mover.getNavigation().stop();
        Villages.recordDeath(from.id());
        Villages.recordBirth(to.id());
        Annals.moved(from.id(), to.id());
        Gatherings.pledged(to.id(), mover, stays, day);
        tie(a.id(), b.id());
        String line = mover.displayNameCap() + " of " + Villages.name(from.id()) + " married " + stays.displayNameCap()
            + " of " + Villages.name(to.id()) + ", and went to live in " + Villages.name(to.id());
        Villages.tell(a.id(), day, line);
        Villages.tell(b.id(), day, line);
        remember(a.id(), b.id(), day, 8, mover.displayNameCap() + " and " + stays.displayNameCap() + " were married");
        mover.persona().remember(day, "I married " + stays.displayNameCap() + " and came to live in " + Villages.name(to.id()), 9);
        stays.persona().remember(day, "I married " + mover.displayNameCap() + " of " + Villages.name(from.id()), 9);
        Ledger.relate(a.id(), b.id(), 10);
        return new VillageFolkEntity[]{ mover, stays };
    }

    // ------------------------------------------------------------------ the day's dealings

    /**
     * One day of the bonds between two neighbours (from Diplomacy.daily, before the day's change is
     * written). Returns what they add to the change.
     */
    static int daily(ServerLevel level, Villages.Village a, Villages.Village b, long day, int r, boolean crowded, Random rng) {
        UUID x = a.id(), y = b.id();
        int delta = 0;
        Diplomacy.Terms t = Diplomacy.terms(r);
        // The border: neighbours not at each other's throats walk the line and agree it.
        if (crowded && !border(x, y) && r > Diplomacy.UNEASY && rng.nextInt(6) == 0) {
            agreeBorder(x, y, day);
            delta += 5;
        }
        // Marriages bind them: a little warmer every day for each.
        delta += Math.min(3, ties(x, y));
        // A go-between: two at odds who both get on with a third.
        if (t.ordinal() >= Diplomacy.Terms.UNEASY.ordinal() && rng.nextInt(4) == 0) delta += mediate(a, b, day);
        // A wedding across the boundary.
        if (r >= Diplomacy.FRIENDLY && rng.nextInt(12) == 0) marry(level, a, b, day, rng);      // (it warms them itself)
        // The weekly feast: friends send a guest with a gift.
        if (day % 7 == 6 && r >= Diplomacy.FRIENDLY && !Caravans.between(x, y) && rng.nextInt(2) == 0) {
            boolean aGives = rng.nextBoolean();
            if (com.jrpetty.mcassistant.AssistantConfig.villagesShareGoods()
                    && Envoys.send(level, aGives ? a : b, aGives ? b : a, Envoys.Errand.GIFT, day)) {
                remember(x, y, day, 3, Villages.name(aGives ? x : y) + " sent a guest to the feast with a gift");
            }
        }
        // A hand when short: what one can spare, of what the other wants.
        if (r >= Diplomacy.FRIENDLY && day % 3 == 0 && com.jrpetty.mcassistant.AssistantConfig.villagesShareGoods()) delta += lendAHand(level, a, b, day);
        // The harvest contest.
        if (day % 7 == 3 && r > Diplomacy.UNEASY) delta += contest(a, b, day);
        // Word of a player spreads between allies, and between enemies.
        if (t == Diplomacy.Terms.ALLIES || t == Diplomacy.Terms.FEUD) spreadWord(level, x, y, t == Diplomacy.Terms.ALLIES);
        return delta;
    }

    /** A third village on good terms with both brings two at odds to terms. Returns the change. */
    static int mediate(Villages.Village a, Villages.Village b, long day) {
        UUID x = a.id(), y = b.id();
        for (Villages.Village c : Villages.every()) {
            if (c.id().equals(x) || c.id().equals(y) || !c.dim().equals(a.dim())) continue;
            if (!Ledger.knowEachOther(x, c.id()) || !Ledger.knowEachOther(y, c.id())) continue;
            if (Ledger.relation(x, c.id()) < Diplomacy.FRIENDLY || Ledger.relation(y, c.id()) < Diplomacy.FRIENDLY) continue;
            String cn = Villages.name(c.id());
            String line = "the elder of " + cn + " brought " + Villages.name(x) + " and " + Villages.name(y)
                + " together and talked them round";
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            Villages.tell(c.id(), day, "our elder made peace between " + Villages.name(x) + " and " + Villages.name(y));
            Ledger.relate(x, c.id(), 3);
            Ledger.relate(y, c.id(), 3);
            callTruce(x, y, day);
            remember(x, y, day, 6, cn + " made peace between us");
            return 15;
        }
        return 0;
    }

    /** Friends send what they can spare of what the other is short of. Returns the change. */
    static int lendAHand(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        for (int turn = 0; turn < 2; turn++) {
            Villages.Village giver = turn == 0 ? a : b, taker = turn == 0 ? b : a;
            java.util.EnumSet<Villages.Task> wants = java.util.EnumSet.noneOf(Villages.Task.class);
            for (Villages.Need n : Villages.needs(level, taker.id())) wants.add(n.task());
            if (wants.isEmpty()) continue;
            for (Market.Good g : Market.GOODS) {
                if (g.need() == Villages.Task.NONE || !wants.contains(g.need())) continue;
                ItemStack sample = null;
                for (BlockPos p : Villages.storeChests(level, giver.id())) {
                    if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
                    for (int i = 0; i < c.getContainerSize() && sample == null; i++) {
                        if (g.what().test(c.getItem(i))) sample = c.getItem(i).copyWithCount(1);
                    }
                    if (sample != null) break;
                }
                if (sample == null) continue;
                int spare = Budget.spare(level, giver.id(), sample);
                int n = Math.min(spare, g.bundle() * 2);
                if (n < g.bundle()) continue;
                final ItemStack kind = sample;
                if (!TownWork.take(level, giver, s -> ItemStack.isSameItemSameComponents(s, kind), n)) continue;
                ItemStack left = Market.intoStores(level, taker.id(), kind.copyWithCount(n));
                if (!left.isEmpty()) Market.intoStores(level, giver.id(), left);
                int sent = n - left.getCount();
                if (sent <= 0) continue;
                Budget.forget(giver.id());
                Budget.forget(taker.id());
                String what = sent + " " + g.name().toLowerCase(Locale.ROOT);
                Villages.tell(taker.id(), day, "our friends in " + Villages.name(giver.id()) + " sent " + what + " we were short of");
                Villages.tell(giver.id(), day, "we sent " + what + " we could spare to " + Villages.name(taker.id()));
                remember(a.id(), b.id(), day, 4, Villages.name(giver.id()) + " sent " + what + " when we were short");
                return 3;
            }
        }
        return 0;
    }

    /** Who made more this week? The winner crows; a prickly elder loses badly. Returns the change. */
    static int contest(Villages.Village a, Villages.Village b, long day) {
        int ma = Economy.weekAverage(a.id()), mb = Economy.weekAverage(b.id());
        if (ma <= 0 || mb <= 0 || ma == mb) return 0;
        Villages.Village win = ma > mb ? a : b, lose = ma > mb ? b : a;
        String wn = Villages.name(win.id()), ln = Villages.name(lose.id());
        Villages.tell(win.id(), day, "we out-worked " + ln + " this week, " + Math.max(ma, mb) + " a day to their " + Math.min(ma, mb));
        Villages.tell(lose.id(), day, wn + " out-worked us this week — " + Math.max(ma, mb) + " a day to our " + Math.min(ma, mb) + ". Next week.");
        boolean sore = Envoys.temper(lose.id()) == Envoys.Temper.PRICKLY;
        remember(a.id(), b.id(), day, sore ? -2 : 1, wn + " won the week's contest");
        return sore ? -3 : 2;
    }

    /** A player honoured (or worse) in one village: its allies (or enemies) hear of it. */
    static void spreadWord(ServerLevel level, UUID x, UUID y, boolean allies) {
        for (Player p : level.players()) {
            for (int side = 0; side < 2; side++) {
                UUID from = side == 0 ? x : y, to = side == 0 ? y : x;
                Standing.Title there = Standing.of(from, p.getUUID(), level.getGameTime()).title();
                int warmth;
                if (there.atLeast(Standing.Title.HONOURED)) warmth = allies ? 2 : -2;
                else if (there == Standing.Title.OUTCAST) warmth = allies ? -2 : 1;   // the enemy of our enemy
                else continue;
                int told = 0;
                for (AssistantEntity a : Villages.folkOf(to)) {
                    if (told >= 3 || !(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
                    f.persona().feelFor(p.getUUID(), p.getName().getString(), warmth);
                    told++;
                }
                if (told > 0) Standing.stir(to, p.getUUID());
            }
        }
    }

    /** Tests: a go-between for these two, now. */
    public static int mediateForTests(Villages.Village a, Villages.Village b, long day) {
        return mediate(a, b, day);
    }

    /** Tests: the day's change as the truce lets it stand. */
    public static int underTruceForTests(UUID x, UUID y, long day, int r, int delta) {
        return underTruce(x, y, day, r, delta);
    }

    /** The truce's floor under the day's change: no falling back into a feud while it lasts. */
    static int underTruce(UUID x, UUID y, long day, int r, int delta) {
        if (!truce(x, y, day)) return delta;
        return r + delta <= Diplomacy.FEUD ? Diplomacy.FEUD + 1 - r : delta;
    }

    // ------------------------------------------------------------------ letters

    /** "Could I carry a letter for you?" — the elder writes one to a neighbour, for the player to take. */
    public static String letter(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A letter? To whom? I've no village to write from.";
        Villages.Village o = Diplomacy.meant(village, text);
        if (o == null) return "There's nobody near enough to write to.";
        long day = level.getDayTime() / 24000L;
        String key = "letter/" + p.getUUID() + "/" + o.id();
        String last = Ledger.note(village, key);
        try {
            if (last != null && !last.isEmpty() && day - Long.parseLong(last) < 3) {
                return "You've a letter of ours to " + Villages.name(o.id()) + " already. Take that one first.";
            }
        } catch (NumberFormatException ignored) {
            // write another
        }
        if (Diplomacy.terms(village, o.id()) == Diplomacy.Terms.FEUD) {
            return "Write to " + Villages.name(o.id()) + "? After what they did? Not a word.";
        }
        Ledger.note(village, key, Long.toString(day));
        String from = Villages.name(village), to = Villages.name(o.id());
        String elder = Villages.elderName(village);
        ItemStack book = Services.book("A letter for " + to, elder.isEmpty() ? from : elder,
            "To the elder of " + to + ",\n\nGreetings from " + from + ". We hope the harvest finds you well, and that our two villages "
                + "may go on " + (Ledger.relation(village, o.id()) >= Diplomacy.FRIENDLY ? "the good friends we are." : "better friends than we have been.")
                + "\n\n— " + (elder.isEmpty() ? "the folk of " + from : elder + ", elder of " + from), List.of());
        CompoundTag tag = new CompoundTag();
        tag.putString("mca_letter_from", village.toString());
        tag.putString("mca_letter_to", o.id().toString());
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (!p.getInventory().add(book)) p.drop(book, false);
        return "Here — take this to the elder of " + to + ", " + (Diplomacy.apart(Villages.get(village), o) / 10 * 10) + " blocks "
            + Diplomacy.way(Villages.get(village), o) + ". Anybody there will see it gets to them. They'll thank you for the walk.";
    }

    /**
     * A letter handed to a folk: if it is addressed to its village, it is delivered — the two
     * villages the warmer for it, and the carrier paid for the walk. Null if this is no letter
     * for here (the gift goes on as any gift would).
     */
    @Nullable
    public static String deliver(VillageFolkEntity f, Player p, ItemStack held) {
        CustomData data = held.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains("mca_letter_to") || !tag.contains("mca_letter_from")) return null;
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return null;
        UUID to, from;
        try {
            to = UUID.fromString(tag.getString("mca_letter_to"));
            from = UUID.fromString(tag.getString("mca_letter_from"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!to.equals(village)) {
            return "This is for the elder of " + Villages.name(to) + ", not for us. You'll want to take it there.";
        }
        long day = level.getDayTime() / 24000L;
        held.shrink(1);
        String name = p.getName().getString();
        int after = Ledger.relate(village, from, 8);
        Diplomacy.announce(village, from, after, day);
        remember(village, from, day, 4, name + " carried a letter between us");
        int paid = Math.min(3, Ledger.coins(village));
        if (paid > 0) {
            Ledger.takeCoins(village, paid);
            ItemStack coins = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), paid);
            if (!p.getInventory().add(coins)) p.drop(coins, false);
        }
        for (UUID v : new UUID[]{ village, from }) {
            for (AssistantEntity a : Villages.folkOf(v)) {
                if (a instanceof VillageFolkEntity g && !g.isBaby()) {
                    g.persona().feelFor(p.getUUID(), name, 2);
                }
            }
            Standing.stir(v, p.getUUID());
        }
        Villages.tell(village, day, name + " brought us a letter from " + Villages.name(from));
        Villages.tell(from, day, name + " carried our letter to " + Villages.name(village));
        return "A letter from " + Villages.name(from) + "? How kind of them — and of you, for the walk. "
            + (paid > 0 ? "Here's " + paid + (paid == 1 ? " coin" : " coins") + " for your trouble." : "We've no coin to spare, but thank you.");
    }

    /**
     * "Could you make a trade pact with X?" — from a player honoured in both villages, on good
     * terms with each other: the pact is made on the player's word.
     */
    public static String broker(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A pact? I've no village to make one for.";
        Villages.Village o = Diplomacy.meant(village, text);
        if (o == null) return "With whom? There's nobody near.";
        String on = Villages.name(o.id());
        if (Envoys.pact(village, o.id())) return "We trade with " + on + " already.";
        long now = level.getGameTime();
        boolean here = Standing.of(village, p.getUUID(), now).title().atLeast(Standing.Title.HONOURED);
        boolean there = Standing.of(o.id(), p.getUUID(), now).title().atLeast(Standing.Title.HONOURED);
        if (!here || !there) {
            return "A pact with " + on + " on your word? You'd need to be held in honour here and in " + on + " both, and "
                + (here ? "they don't know you that well yet." : "we don't know you that well yet.");
        }
        if (Ledger.relation(village, o.id()) < Diplomacy.FRIENDLY) {
            return "Not even on your word — we're not on good enough terms with " + on + " for that yet.";
        }
        long day = level.getDayTime() / 24000L;
        Envoys.sign(village, o.id(), day);
        remember(village, o.id(), day, 5, p.getName().getString() + " brokered our trade pact");
        Ledger.relate(village, o.id(), 5);
        return "On your word, then. We'll trade with " + on + " — our caravans will be on the road within the week.";
    }

    // ------------------------------------------------------------------ talk and the books

    /** Today, by the overworld's clock (for the report and the status, which have no level to hand). */
    static long today() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.overworld().getDayTime() / 24000L;
    }

    /** What a folk says of its village's bonds with a neighbour, after the terms (Diplomacy.rivals). */
    static String about(UUID village, Villages.Village o, long day) {
        StringBuilder sb = new StringBuilder();
        if (border(village, o.id())) sb.append("We've a border with them, stones and all. ");
        if (truce(village, o.id(), day)) sb.append("There's a truce on. ");
        int ties = ties(village, o.id());
        if (ties > 0) sb.append(ties == 1 ? "One of ours married one of theirs. " : ties + " marriages between us now. ");
        List<Memory> ms = memories(village, o.id());
        if (!ms.isEmpty()) {
            Memory m = ms.get(ms.size() - 1);
            sb.append("Last I heard: ").append(m.what()).append(". ");
        }
        return sb.toString();
    }

    /** The bonds, in a few words, for the report and the status. */
    static String words(UUID a, UUID b, long day) {
        List<String> out = new ArrayList<>();
        if (border(a, b)) out.add("border");
        if (truce(a, b, day)) out.add("truce");
        if (Envoys.pact(a, b)) out.add("trade pact");
        if (Envoys.allied(a, b)) out.add("alliance");
        int ties = ties(a, b);
        if (ties > 0) out.add(ties + (ties == 1 ? " marriage" : " marriages"));
        int feel = feeling(a, b, day);
        if (feel >= 10) out.add("warm memories");
        else if (feel <= -10) out.add("a grudge");
        return String.join(", ", out);
    }
}
