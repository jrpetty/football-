package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villages and their neighbours (Ledger.relations, -100 to 100).
 * <ul>
 * <li><b>Land disputes.</b> Two villages whose lands overlap quarrel over the ground between
 *     them, a little more every day, until they fall out or somebody makes peace.</li>
 * <li><b>Kin and trade.</b> A colony and the village it came from start as family, and the
 *     caravans that run between them keep them friendly.</li>
 * <li><b>Alliances.</b> Neighbours on the best of terms swear an alliance: each sleeps
 *     sounder for the other (contentment) and sends help when the other goes hungry.</li>
 * <li><b>Feuds.</b> Neighbours on the worst of terms fall into a feud: insults, stolen sheep,
 *     a fight at the boundary stone — every one of them in both villages' history.</li>
 * <li><b>Tribute.</b> A much bigger village that thinks little of a smaller one demands
 *     tribute every week, out of the smaller one's treasury. Pay, and there is peace of a
 *     kind; refuse (or be too poor), and it gets worse.</li>
 * <li><b>A player can settle a quarrel or stir one up</b>: carry an olive branch (ten coins
 *     for the gifts) from any folk to its neighbours, or whisper rumours about them — which
 *     works, unless somebody finds out who started them.</li>
 * </ul>
 */
public final class Diplomacy {

    private Diplomacy() {}

    /** How far apart two villages can be and still have dealings. */
    public static final int NEAR = 640;
    /** Closer than this, their lands overlap and there is ground to quarrel over. */
    public static final int CROWDED = Villages.VILLAGE_RANGE * 2 + 48;
    public static final int ALLIANCE = 60, FRIENDLY = 20, UNEASY = -20, FEUD = -50;
    /** What carrying an olive branch costs a player (the gifts that go with it). */
    public static final int PEACE_COST = 10;

    public enum Terms {
        ALLIES("allies"), FRIENDS("on good terms"), NEUTRAL("neither here nor there"),
        UNEASY("on uneasy terms"), FEUD("in a feud");

        public final String words;
        Terms(String words) { this.words = words; }
    }

    public static Terms terms(int r) {
        return r >= ALLIANCE ? Terms.ALLIES : r >= FRIENDLY ? Terms.FRIENDS : r > UNEASY ? Terms.NEUTRAL
            : r > FEUD ? Terms.UNEASY : Terms.FEUD;
    }

    public static Terms terms(UUID a, UUID b) { return terms(Ledger.relation(a, b)); }

    /** The day each pair last had its dealings, and the terms they were on (for news of a change). */
    private static final Map<String, Long> DAY = new ConcurrentHashMap<>();
    private static final Map<String, Terms> WAS = new ConcurrentHashMap<>();
    /** The day a village last paid (or refused) tribute. */
    private static final Map<UUID, Long> TRIBUTE = new ConcurrentHashMap<>();
    /** The day a village last felt a feud or a tribute bite: its folk are sore about it. */
    private static final Map<UUID, Long> SORE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DAY.clear();
        WAS.clear();
        TRIBUTE.clear();
        SORE.clear();
    }

    /** Is the village sore today, over a feud or a tribute? (a mood for its folk) */
    public static boolean sore(UUID village, long day) {
        return SORE.getOrDefault(village, -100L) >= day - 1;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 200 != 71) return;
        Guard.run("diplomacy", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                long t = level.getDayTime() % 24000L;
                if (t < 1000L || t > 11000L) continue;                    // dealings are done by day
                long day = level.getDayTime() / 24000L;
                List<Villages.Village> here = new ArrayList<>();
                for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) here.add(v);
                for (int i = 0; i < here.size(); i++) {
                    for (int j = i + 1; j < here.size(); j++) {
                        Villages.Village a = here.get(i), b = here.get(j);
                        if (!neighbours(a, b)) continue;
                        String key = Ledger.pair(a.id(), b.id());
                        if (DAY.getOrDefault(key, -1L) >= day) continue;
                        DAY.put(key, day);
                        daily(level, a, b, day);
                    }
                }
            }
        });
    }

    public static boolean neighbours(Villages.Village a, Villages.Village b) {
        if (!a.dim().equals(b.dim()) || a.id().equals(b.id())) return false;
        int d = apart(a, b);
        double far = Math.max(Fame.reach(a.id()), Fame.reach(b.id()));     // [identity] a city's envoys come from afar
        return d <= NEAR * far || d <= NEAR * 2 * far && Scouts.met(a.id(), b.id());   // a town the scouts found is a neighbour further off
    }

    static int apart(Villages.Village a, Villages.Village b) {
        return (int) Math.sqrt(a.centre().distSqr(b.centre()));
    }

    static boolean kin(UUID a, UUID b) {
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            if ((link.getKey().equals(a) && link.getValue().equals(b)) || (link.getKey().equals(b) && link.getValue().equals(a))) return true;
        }
        return false;
    }

    /** One day's dealings between two neighbours. Returns the change. */
    public static int daily(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        UUID x = a.id(), y = b.id();
        String an = Villages.name(x), bn = Villages.name(y);
        if (!Ledger.knowEachOther(x, y)) {
            int start = kin(x, y) ? 40 : 0;
            Ledger.relate(x, y, start);
            String line = kin(x, y) ? an + " and " + bn + " are kin, the one born of the other"
                : "folk from " + an + " and " + bn + " met for the first time";
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            WAS.put(Ledger.pair(x, y), terms(start));
            return start;
        }
        int r = Ledger.relation(x, y);
        int delta = 0;
        long seed = (long) Ledger.pair(x, y).hashCode() * 31L + day;
        java.util.Random rng = new java.util.Random(seed);
        // Kin, and the caravans between them.
        if (kin(x, y)) delta += 2;
        delta += Ethos.relationLean(x, y);                  // [identity] two peaceable towns, a closed one, a town of renown, Peacemakers
        // The ground between them.
        boolean crowded = apart(a, b) < CROWDED;
        boolean truce = Bonds.truce(x, y, day);
        // A border agreed (Bonds) ends the quarrel over the ground.
        if (crowded && !Bonds.border(x, y)) {
            delta -= 4;
            if (rng.nextInt(5) == 0) {
                String line = pick(rng, "folk from " + an + " and " + bn + " argued over where the one's land ends and the other's begins",
                    "a boundary stone between " + an + " and " + bn + " was moved in the night",
                    bn + "'s woodcutters were caught felling trees " + an + " calls its own");
                Villages.tell(x, day, line);
                Villages.tell(y, day, line);
                Bonds.remember(x, y, day, -2, "we quarrelled over the boundary");
                delta -= 2;
            }
        }
        // Something happened between them today?
        int roll = rng.nextInt(10);
        if (Wars.atWar(x, y)) roll = 9;                     // [war-peace] at war: no market days, courting or truces by chance (WarAndPeace)
        Terms now = terms(r);
        if (roll == 0) {
            delta += 6;
            Villages.tell(x, day, "traders from " + bn + " came to market, and went home pleased");
            Villages.tell(y, day, "our traders did good business in " + an);
            Bonds.remember(x, y, day, 3, "traders from " + bn + " did good business in " + an);
        } else if (roll == 1 && now.ordinal() <= Terms.NEUTRAL.ordinal()) {
            delta += 4;
            String line = "a lad from " + an + " came courting a girl from " + bn;
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            Bonds.remember(x, y, day, 2, "there was courting between us");
        } else if (roll == 2 && now.ordinal() >= Terms.NEUTRAL.ordinal() && !truce) {
            delta -= 6;
            String line = pick(rng, "a sheep went missing, and " + an + " blames " + bn,
                "somebody from " + bn + " said something unforgivable about " + an + "'s cooking",
                "there was a scuffle between young folk from " + an + " and " + bn + " at the boundary");
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            Bonds.remember(x, y, day, -4, line);
        } else if (roll == 3 && now == Terms.FEUD && !truce) {
            // A feud flares: a scrap at the boundary, and both villages' folk feel it.
            String line = "folk from " + an + " and " + bn + " came to blows at the boundary — the feud goes on";
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            SORE.put(x, day);
            SORE.put(y, day);
            Bonds.remember(x, y, day, -5, "we came to blows at the boundary");
            delta -= 3;
        } else if (roll == 4 && now == Terms.FEUD && rng.nextInt(truceOdds(x, y)) == 0) {
            // Even a feud wears itself out in the end.
            delta += 30;
            String line = "the elders of " + an + " and " + bn + " met at the boundary stone and agreed a truce";
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            Bonds.callTruce(x, y, day);
            Bonds.remember(x, y, day, 5, "our elders agreed a truce");
        }
        // Allies look after each other: the stronger feeds the hungrier.
        // (Only where villages may send each other goods outright: AssistantConfig.villagesShareGoods.
        // Every village is its own otherwise, and the food and the coin stay where they are.)
        boolean share = com.jrpetty.mcassistant.AssistantConfig.villagesShareGoods();
        if (share && (now == Terms.ALLIES || Envoys.allied(x, y))) helpAlly(level, a, b, day);
        // Tribute, once a week — if the bigger village's elder is the sort to ask for it.
        if (share) delta += tribute(level, a, b, day, rng);
        // Who leads them: a warm-hearted elder makes friends, a prickly one enemies; two elders
        // alike get on, two opposites do not.
        delta += Envoys.temper(x).warmth + Envoys.temper(y).warmth + Envoys.chemistry(x, y);
        // [perks] Open Borders (Tariffs cool it), a Diplomat in office and the legacies of peace: every other day.
        delta += Perks.warmth(x, day) + Perks.warmth(y, day);
        // Memories, borders, truces, marriages, feasts, contests, a hand when short (Bonds).
        delta += Bonds.daily(level, a, b, day, r, crowded, rng);
        // With nothing to keep it hot or cold, a relation drifts back toward nothing — unless the
        // memory is warm: a fresh kindness or a fresh grudge holds it where it is.
        if (delta == 0 && r != 0 && !kin(x, y) && !Envoys.pact(x, y) && Math.abs(Bonds.feeling(x, y, day)) < 10) delta = r > 0 ? -1 : 1;
        delta = Bonds.underTruce(x, y, day, r, delta);
        if (Wars.atWar(x, y)) delta = Math.min(0, delta);   // [war-peace] no warming between towns at war: only the treaty mends it
        int after = Ledger.relate(x, y, delta);
        announce(x, y, after, day);
        // And the elders send their envoys: greetings, trade, alliances, peace, tribute, complaints.
        Envoys.consider(level, a, b, day, rng);
        return delta;
    }

    /** How hard a feud is to end: easy between forgiving elders, hard between prickly ones. */
    private static int truceOdds(UUID x, UUID y) {
        int odds = 3;
        for (UUID v : new UUID[]{ x, y }) {
            Envoys.Temper t = Envoys.temper(v);
            if (t == Envoys.Temper.PRICKLY) odds += 3;
            else if (t == Envoys.Temper.EASY || t == Envoys.Temper.WARM || t == Envoys.Temper.GENEROUS) odds -= 1;
        }
        return Math.max(1, odds);
    }

    /** News when two neighbours' terms change: an alliance sworn, a feud begun, a feud ended. */
    static void announce(UUID x, UUID y, int r, long day) {
        String key = Ledger.pair(x, y);
        Terms t = terms(r), was = WAS.put(key, t);
        if (was == null || was == t) return;
        Envoys.onTerms(x, y, t, day);
        String an = Villages.name(x), bn = Villages.name(y);
        String line = null;
        if (t == Terms.ALLIES) line = an + " and " + bn + " swore an alliance";
        else if (t == Terms.FEUD) line = an + " and " + bn + " fell into a feud";
        else if (was == Terms.FEUD) line = "the feud between " + an + " and " + bn + " cooled";
        else if (was == Terms.ALLIES) line = "the alliance between " + an + " and " + bn + " lapsed";
        if (line == null) return;
        Villages.tell(x, day, line);
        Villages.tell(y, day, line);
    }


    /** An ally in want: the better-fed village sends food from its stores. */
    static void helpAlly(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        Contentment.View va = Contentment.of(level, a.id()), vb = Contentment.of(level, b.id());
        if (va == null || vb == null) return;
        Villages.Village giver = null, taker = null;
        if (va.food() <= 8 && vb.food() >= 18) { giver = b; taker = a; }
        else if (vb.food() <= 8 && va.food() >= 18) { giver = a; taker = b; }
        if (giver == null) return;
        int sent = sendFood(level, giver.id(), taker.id(), 16);
        if (sent <= 0) return;
        Villages.tell(taker.id(), day, "our allies in " + Villages.name(giver.id()) + " sent " + sent + " food when we were hungry");
        Villages.tell(giver.id(), day, "we sent " + sent + " food to our hungry allies in " + Villages.name(taker.id()));
        Bonds.remember(a.id(), b.id(), day, 6, Villages.name(giver.id()) + " fed us when we were hungry");
        Ledger.relate(a.id(), b.id(), 3);
    }

    /** Move up to {@code most} food from one village's stores into the other's. Returns how much went. */
    static int sendFood(ServerLevel level, UUID from, UUID to, int most) {
        List<net.minecraft.core.BlockPos> give = Villages.storeChests(level, from), take = Villages.storeChests(level, to);
        if (give.isEmpty() || take.isEmpty()) return 0;
        int sent = 0;
        for (net.minecraft.core.BlockPos g : give) {
            if (sent >= most || !(level.getBlockEntity(g) instanceof net.minecraft.world.Container src)) continue;
            for (int i = 0; i < src.getContainerSize() && sent < most; i++) {
                net.minecraft.world.item.ItemStack s = src.getItem(i);
                if (s.isEmpty() || s.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
                net.minecraft.world.item.ItemStack moving = s.split(Math.min(s.getCount(), most - sent));
                int n = moving.getCount();
                for (net.minecraft.core.BlockPos t : take) {
                    if (moving.isEmpty()) break;
                    if (level.getBlockEntity(t) instanceof net.minecraft.world.Container dst) {
                        moving = put(dst, moving);
                    }
                }
                if (!moving.isEmpty()) { s.grow(moving.getCount()); n -= moving.getCount(); }
                sent += n;
                src.setChanged();
            }
        }
        return sent;
    }

    /** Onto the part stacks of the same first, then empty slots (Stacking). Returns what would not fit. */
    private static net.minecraft.world.item.ItemStack put(net.minecraft.world.Container c, net.minecraft.world.item.ItemStack s) {
        return Stacking.insert(c, s);
    }

    /** Is a tribute due from this village (a week since it last paid or refused)? */
    static boolean tributeDue(UUID small, long day) {
        return day - TRIBUTE.getOrDefault(small, -100L) >= 7;
    }

    /** What a village asks in tribute: more, the bigger it is. */
    static int tributeAsked(UUID big) {
        return 4 + Villages.folkOf(big).size() / 4;
    }

    /** It paid (or refused): no more asking for a week, and it is sore about it. */
    static void paidTribute(UUID small, long day) {
        TRIBUTE.put(small, day);
        SORE.put(small, day);
    }

    /**
     * A much bigger neighbour that thinks little of a smaller one wants tribute, once a week —
     * if its elder is shrewd or prickly; a kindly one never asks. It sends an envoy to ask in
     * person (Envoys) when it can; if nobody can go, the demand comes by word of mouth.
     */
    static int tribute(ServerLevel level, Villages.Village a, Villages.Village b, long day, java.util.Random rng) {
        int r = Ledger.relation(a.id(), b.id());
        if (r > 0) return 0;
        int na = Villages.folkOf(a.id()).size(), nb = Villages.folkOf(b.id()).size();
        Villages.Village big, small;
        if (na >= nb * 3 / 2 + 2 && Villages.ageOf(a.id()).ordinal() >= Villages.ageOf(b.id()).ordinal()) { big = a; small = b; }
        else if (nb >= na * 3 / 2 + 2 && Villages.ageOf(b.id()).ordinal() >= Villages.ageOf(a.id()).ordinal()) { big = b; small = a; }
        else return 0;
        Envoys.Temper bt = Envoys.temper(big.id());
        if (bt != Envoys.Temper.SHREWD && bt != Envoys.Temper.PRICKLY) return 0;
        if (!tributeDue(small.id(), day)) return 0;
        TRIBUTE.put(small.id(), day);
        if (!Caravans.between(big.id(), small.id()) && Envoys.send(level, big, small, Envoys.Errand.TRIBUTE, day)) return 0;
        int want = tributeAsked(big.id());
        String bn = Villages.name(big.id()), sn = Villages.name(small.id());
        if (Ledger.coins(small.id()) >= want && rng.nextInt(4) != 0) {
            Ledger.takeCoins(small.id(), want);
            Ledger.addCoins(big.id(), want);
            Villages.tell(small.id(), day, "we paid " + bn + " " + want + " coins in tribute, and resent every one");
            Bonds.remember(big.id(), small.id(), day, -3, sn + " paid " + bn + " tribute");
            Villages.tell(big.id(), day, sn + " paid us " + want + " coins in tribute");
            SORE.put(small.id(), day);
            return 3;                                                    // an uneasy peace
        }
        Villages.tell(small.id(), day, bn + " demanded " + want + " coins in tribute, and we would not pay");
        Bonds.remember(big.id(), small.id(), day, -6, bn + " demanded tribute and " + sn + " refused");
        Villages.tell(big.id(), day, sn + " refused us our tribute");
        return -12;
    }

    // ------------------------------------------------------------------ contentment

    /** What a village's neighbours do for its peace of mind: allies help, feuds hurt. */
    public static int safety(UUID village, List<String> good, List<String> bad) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        int out = 0;
        boolean ally = false, feud = false;
        for (Villages.Village o : Villages.every()) {
            if (!neighbours(v, o) || !Ledger.knowEachOther(village, o.id())) continue;
            Terms t = terms(village, o.id());
            if (t == Terms.ALLIES) ally = true;
            if (t == Terms.FEUD) feud = true;
        }
        if (ally) { out += 2; good.add("good allies"); }
        if (feud) { out -= 3; bad.add("the feud"); }
        return out;
    }

    // ------------------------------------------------------------------ talk

    /** The neighbours a village knows of, nearest first. */
    public static List<Villages.Village> neighboursOf(UUID village) {
        Villages.Village v = Villages.get(village);
        List<Villages.Village> out = new ArrayList<>();
        if (v == null) return out;
        for (Villages.Village o : Villages.every()) if (neighbours(v, o)) out.add(o);
        out.sort(java.util.Comparator.comparingInt(o -> apart(v, o)));
        return out;
    }

    /** Which neighbour the words name — or, if none, the nearest. */
    @Nullable
    static Villages.Village meant(UUID village, String text) {
        List<Villages.Village> ns = neighboursOf(village);
        String t = text.toLowerCase(Locale.ROOT);
        for (Villages.Village o : ns) if (t.contains(Villages.name(o.id()).toLowerCase(Locale.ROOT))) return o;
        return ns.isEmpty() ? null : ns.get(0);
    }

    static String way(Villages.Village from, Villages.Village to) {
        int dx = to.centre().getX() - from.centre().getX(), dz = to.centre().getZ() - from.centre().getZ();
        String ns = Math.abs(dz) * 2 < Math.abs(dx) ? "" : dz < 0 ? "north" : "south";
        String ew = Math.abs(dx) * 2 < Math.abs(dz) ? "" : dx < 0 ? "west" : "east";
        return ns + ew;
    }

    /** "What do you think of the other villages?" */
    public static String rivals(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "Other villages? I've enough to think about with no village of my own.";
        Villages.Village v = Villages.get(village);
        List<Villages.Village> ns = neighboursOf(village);
        if (v == null || ns.isEmpty()) return "There's nobody else for miles. Just us.";
        StringBuilder sb = new StringBuilder();
        int told = 0;
        for (Villages.Village o : ns) {
            if (told == 3) break;
            String on = Villages.name(o.id());
            String where = on + ", " + (apart(v, o) / 10 * 10) + " blocks " + way(v, o);
            if (!Ledger.knowEachOther(village, o.id())) {
                sb.append("There's ").append(where).append(" — we've not had dealings yet. ");
            } else {
                int r = Ledger.relation(village, o.id());
                sb.append(switch (terms(r)) {
                    case ALLIES -> "Our allies in " + where + ". Good folk; they'd stand by us. ";
                    case FRIENDS -> "We get on with " + where + ". ";
                    case NEUTRAL -> where + ": we keep ourselves to ourselves. ";
                    case UNEASY -> "Don't talk to me about " + where + ". There's bad blood. ";
                    case FEUD -> "We're in a feud with " + where + ", and I won't forgive them. ";
                });
                if (apart(v, o) < CROWDED && r < FRIENDLY && !Bonds.border(village, o.id())) sb.append("They're too close — that's half the trouble. ");
                sb.append(Bonds.about(village, o, f.level().getDayTime() / 24000L));
            }
            told++;
        }
        String lead = Envoys.leaderNote(village);
        if (!lead.isEmpty()) sb.append(lead).append(' ');
        for (Villages.Village o : ns) {
            if (Envoys.allied(village, o.id())) sb.append("We're sworn allies with ").append(Villages.name(o.id())).append(". ");
            else if (Envoys.pact(village, o.id())) sb.append("Our caravans trade with ").append(Villages.name(o.id())).append(". ");
        }
        return sb.toString().trim();
    }

    /** A player carries an olive branch to a neighbour. */
    public static String peace(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "Make peace? I've no village to make it for.";
        Villages.Village o = meant(village, text);
        if (o == null) return "With whom? There's nobody near enough to quarrel with.";
        String on = Villages.name(o.id());
        if (Wars.atWar(village, o.id())) return WarAndPeace.broker(f, p, o);   // [war-peace] at war: a peace on the player's word
        int r = Ledger.relation(village, o.id());
        if (r >= FRIENDLY) return "We're on good terms with " + on + " already.";
        int coins = Market.coinsHeld(p);
        if (coins < PEACE_COST) return "Peace with " + on + "? It would take gifts — " + PEACE_COST + " coins' worth. You've " + coins + ".";
        Market.payOut(p, PEACE_COST);
        Ledger.addCoins(o.id(), PEACE_COST);
        int after = Ledger.relate(village, o.id(), 25);
        long day = f.level().getDayTime() / 24000L;
        String name = p.getName().getString();
        String line = name + " carried gifts between " + Villages.name(village) + " and " + on + " and made peace";
        Villages.tell(village, day, line);
        Villages.tell(o.id(), day, line);
        if (terms(r) == Terms.FEUD) Bonds.callTruce(village, o.id(), day);
        Bonds.remember(village, o.id(), day, 5, name + " made peace between us");
        announce(village, o.id(), after, day);
        for (UUID v : new UUID[]{ village, o.id() }) {
            for (AssistantEntity a : Villages.folkOf(v)) {
                if (a instanceof VillageFolkEntity g) g.persona().feelFor(p.getUUID(), name, 4);
            }
            Standing.stir(v, p.getUUID());
        }
        return terms(after).ordinal() < terms(r).ordinal()
            ? "You did that? Then " + on + " and us are " + terms(after).words + " now. Thank you."
            : "That helped. We're still " + terms(after).words + " with " + on + ", but it's a start.";
    }

    /** A player whispers rumours about a neighbour. Somebody may find out who started them. */
    public static String stir(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "Rumours? About whom?";
        Villages.Village o = meant(village, text);
        if (o == null) return "There's nobody near enough to gossip about.";
        String on = Villages.name(o.id());
        long day = f.level().getDayTime() / 24000L;
        String name = p.getName().getString();
        int after = Ledger.relate(village, o.id(), -20);
        announce(village, o.id(), after, day);
        Bonds.remember(village, o.id(), day, -5, "word went round of what " + on + " says about " + Villages.name(village));
        f.persona().remember(day, name + " told me what " + on + " says about us", 3);
        if (f.getRandom().nextInt(3) == 0) {
            // Found out: both villages think less of the player.
            Ledger.relate(village, o.id(), 10);
            for (UUID v : new UUID[]{ village, o.id() }) {
                for (AssistantEntity a : Villages.folkOf(v)) {
                    if (a instanceof VillageFolkEntity g) g.persona().feelFor(p.getUUID(), name, -10);
                }
                Standing.stir(v, p.getUUID());
                Villages.tell(v, day, name + " was found out spreading lies between " + Villages.name(village) + " and " + on);
            }
            p.sendSystemMessage(Component.literal("Word got round who started the rumours. Neither village thinks well of you now.")
                .withStyle(ChatFormatting.RED));
            return "Wait — " + on + " never said that, did they? You're stirring. Shame on you.";
        }
        Villages.tell(village, day, "word went round of what " + on + " has been saying about us");
        return pick(f.getRandom(), "They said that? About us? Well! We'll see about " + on + ".",
            "I always knew " + on + " couldn't be trusted.");
    }

    /** One line per neighbour pair, for /village relations. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        List<Villages.Village> all = Villages.every();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Villages.Village a = all.get(i), b = all.get(j);
                if (!neighbours(a, b) || !Ledger.knowEachOther(a.id(), b.id())) continue;
                int r = Ledger.relation(a.id(), b.id());
                String bonds = Bonds.words(a.id(), b.id(), Bonds.today());
                out.add(Villages.name(a.id()) + " & " + Villages.name(b.id()) + ": " + terms(r).words + " (" + r + ", "
                    + apart(a, b) + " blocks apart" + (kin(a.id(), b.id()) ? ", kin" : "") + (bonds.isEmpty() ? "" : "; " + bonds) + ")");
            }
        }
        return out;
    }

    /** For the status: how a village stands with its neighbours, in a few words. */
    @Nullable
    public static String status(UUID village) {
        List<String> parts = new ArrayList<>();
        for (Villages.Village o : neighboursOf(village)) {
            if (!Ledger.knowEachOther(village, o.id())) continue;
            String bonds = Bonds.words(village, o.id(), Bonds.today());
            parts.add(Villages.name(o.id()) + " " + terms(village, o.id()).name().toLowerCase(Locale.ROOT)
                + " (" + Ledger.relation(village, o.id()) + (bonds.isEmpty() ? "" : "; " + bonds) + ")");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String pick(java.util.Random r, String... options) {
        return options[r.nextInt(options.length)];
    }

    private static String pick(net.minecraft.util.RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }
}
