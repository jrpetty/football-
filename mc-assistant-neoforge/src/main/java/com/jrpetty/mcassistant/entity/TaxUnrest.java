package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [civic-land] How the town takes its taxes: the unrest, the grumbling, the protest at the hall, and the tax revolt.
 *
 * <p><b>Unrest</b>, nought to a hundred, looked at each morning: what the taxes take of a wage (the head tax and the
 * levy against a week's pay, the share of wages, a little for the toll), heavier where the town's folk care most for
 * their wages and their leisure and lighter under a lord they are used to; the folk in arrears; the wages paid short
 * (a treasury the council kept too empty angers the town too); a war levy with no war. It moves toward that a little a
 * day, faster up than down; a tax raised stirs it, a tax lowered settles it. Past forty, folk grumble (in the street,
 * on their card, in their mood) and keep their coin rather than spend it (TaxBook.holdsBack); the town's contentment
 * suffers (Contentment).
 *
 * <p><b>A protest</b>, past sixty: the angriest of the town (those who care for their wages, the poor, the ones in
 * arrears, the grumpy) gather before the meeting hall's door (or the board) in the late afternoon, signs in their hands
 * (oak signs out of the stores, made of the stores' planks and a stick if there are none, and put back after), and
 * chant; their ringleader speaks for them; the leader comes out and the council answers there and then, by its vote: it
 * gives way (the most hated tax comes down a step) or it does not.
 *
 * <p><b>The revolt</b>: refused, and the anger still past seventy-five two mornings on, the town rises. The angry will
 * not pay on tax day, and the boldest chase the collector off; they strike for a day (no work, a picket before the
 * hall), and again each week it lasts; a stern leader (a lord, a Guardian) sends the watch to walk the collector's
 * round, and then they pay, with bad grace. Three days in, the leader is challenged: an election called early (where
 * the town elects its leader), a vote of no confidence (in a lordship). At the polls, the one who led through it is
 * judged for it and one who stands for wages is heard (Elections.judge). It ends when the council gives way, a new
 * leader cuts the taxes, or the anger cools. All of it is in the chronicle and on the Taxes page.
 */
public final class TaxUnrest {

    private TaxUnrest() {}

    public static final int GRUMBLE = 40, PROTEST = 60, REVOLT = 75;
    /** The most a morning moves the unrest toward what the taxes would make it: up, and down. */
    static final int RISE = 10, FALL = 8;
    /** When a protest gathers: the late afternoon, the day's work mostly done. */
    static final long PROTEST_FROM = 9000L, PROTEST_TO = 11500L;

    /** The signs handed out at a protest (folk id to the town), to be had back. */
    private static final Map<UUID, UUID> SIGNS = new ConcurrentHashMap<>();
    /** The day each folk last paid a tax (for its mood). */
    private static final Map<UUID, Long> TAXED = new ConcurrentHashMap<>();

    static void resetForTests() {
        SIGNS.clear();
        TAXED.clear();
        BURDEN.clear();
    }

    // ------------------------------------------------------------------ reading it

    public static int level(@Nullable UUID village) {
        if (village == null || !CivicLand.live() || !CivicLand.known(village)) return 0;
        return Math.max(0, Math.min(100, CivicLand.town(village).getInt("unrest")));
    }

    public static boolean revolt(@Nullable UUID village) {
        return village != null && CivicLand.live() && CivicLand.known(village) && CivicLand.town(village).getBoolean("revolt");
    }

    /** "calm", "grumbling", "angry", "in revolt". */
    public static String word(UUID village) {
        int u = level(village);
        if (revolt(village)) return "in revolt";
        return u >= PROTEST ? "angry" : u >= GRUMBLE ? "grumbling" : u >= 20 ? "uneasy" : "calm";
    }

    /**
     * What the taxes take, in the hundred of a working folk's pay: the share of wages, the head tax and the levy against
     * a week's wage, and a third of the toll (it falls on the few who sell).
     */
    public static int burden(UUID village) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long beat = server == null ? 0 : server.getTickCount() / 200;           // looked at again every ten seconds at most
        long[] c = BURDEN.get(village);
        if (c != null && c[0] == beat) return (int) c[1];
        int b = burdenNow(village);
        BURDEN.put(village, new long[]{ beat, b });
        return b;
    }

    /** The rates changed: the burden looked at afresh. */
    static void forgetBurden(UUID village) {
        BURDEN.remove(village);
    }

    /** The burden, last looked at, by town: {beat, burden}. */
    private static final Map<UUID, long[]> BURDEN = new ConcurrentHashMap<>();

    static int burdenNow(UUID village) {
        int heads = Math.max(1, CivicLand.grown(village).size());
        double weekWage = 7.0 * Math.max(1, Market.wageBill(village)) / heads;
        double b = LawBook.taxPercent(village) * (Market.taxing(village) ? 1.0 : 0.3)
            + 100.0 * (TaxBook.head(village) + TaxBook.levy(village)) / Math.max(1.0, weekWage)
            + TaxBook.tollPercent(village) / 3.0;
        return (int) Math.round(b);
    }

    // ------------------------------------------------------------------ the round of the town

    /** Every five seconds (CivicLand): the morning's look, a protest when one is due, a grumble now and then. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = CivicLand.day(level), t = CivicLand.time(level);
        CompoundTag tag = CivicLand.town(id);
        if (t >= 1000 && tag.getLong("looked") != day + 1) {
            tag.putLong("looked", day + 1);
            daily(level, v, day);
        }
        // The protest, when one is due, in the late afternoon.
        if (tag.getLong("protestDue") == day + 1 && t >= PROTEST_FROM && t < PROTEST_TO && !Raids.underAlarm(id)
                && Assemblies.underWay(id) == null) {
            tag.remove("protestDue");
            begin(level, v, day);
        }
        // Signs left in hands by a protest that never closed (cut short): back into the stores.
        if (SIGNS.containsValue(id)) {
            Assemblies.Assembly a = Assemblies.underWay(id);
            if (a == null || a.kind != Assemblies.Kind.PROTEST) signsBack(level, v);
        }
        int u = level(id);
        RandomSource r = level.getRandom();
        if (u >= GRUMBLE && t > 1500 && t < 12000 && r.nextInt(revolt(id) ? 8 : 24) == 0) {
            List<VillageFolkEntity> angry = angry(id, CivicLand.grown(id), 6);
            if (!angry.isEmpty()) {
                VillageFolkEntity f = angry.get(r.nextInt(angry.size()));
                if (!f.isSleeping() && !Assemblies.attending(f)) FolkTalk.speak(f, grumble(f, r));
            }
        }
    }

    /** The morning's look: where the unrest is going and why; a protest called, a revolt begun or ended, the leader challenged. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        List<String> why = new ArrayList<>();
        int target = 0;
        int burden = burden(id);
        double heavy = Math.max(0, Math.min(45, (burden - 10) * 2.5));
        if (heavy > 0) {
            List<VillageFolkEntity> grown = CivicLand.grown(id);
            int caring = 0;
            for (VillageFolkEntity f : grown) {
                Values.Value top = Values.top(f);
                if (top == Values.Value.WEALTH || top == Values.Value.LEISURE) caring++;
            }
            double share = grown.isEmpty() ? 0 : caring / (double) grown.size();
            heavy *= 0.8 + 0.6 * share;
            if (Government.of(id) == Government.Form.LORD) heavy *= 0.85;      // a lordship's people are used to its dues
            target += (int) Math.round(heavy);
            why.add("the taxes take " + burden + " in a hundred of a wage");
        }
        int[] arrears = TaxBook.arrearsTotal(id);
        if (arrears[0] > 0) {
            target += Math.min(15, arrears[0] * 3);
            why.add(arrears[0] + (arrears[0] == 1 ? " folk owes" : " folk owe") + " back taxes");
        }
        int share = Market.lastShare(id);
        if (share >= 0 && share < 100) {
            target += (100 - share) / 4;
            why.add("the wages were paid short (" + share + " in a hundred)");
        }
        if (TaxBook.rate(id, TaxBook.Lever.LEVY) > 0 && Wars.enemies(id).isEmpty()) {
            target += 6;
            why.add("a war levy with no war");
        }
        int before = level(id);
        if (tag.getBoolean("revolt")) target += 8;                          // a revolt feeds on itself
        int u = before + Math.max(-FALL, Math.min(RISE, target - before));
        u = Math.max(0, Math.min(100, u));
        tag.putInt("unrest", u);
        tag.putInt("target", target);
        tag.put("why", CivicLand.strings(why));
        tag.putInt("hot", u >= REVOLT ? tag.getInt("hot") + 1 : 0);
        CivicLand.changed();
        // A protest, at most every three days.
        if (u >= PROTEST && day + 1 - tag.getLong("protestDay") >= 3 && angry(id, CivicLand.grown(id), 99).size() >= 3) {
            tag.putLong("protestDue", day + 1);
        }
        // The revolt: a protest refused this week, and the anger past seventy-five two mornings running.
        if (!tag.getBoolean("revolt") && tag.getLong("refusedDay") > 0 && day + 1 - tag.getLong("refusedDay") <= 7 && tag.getInt("hot") >= 2) {
            rise(level, v, day);
        } else if (tag.getBoolean("revolt")) {
            if (u < 50) {
                end(level, v, day, "the anger cooled");
            } else {
                long since = tag.getLong("revoltSince") - 1;
                if (day - since >= 1 && Math.floorMod(day - since, 7L) == 1) tag.putLong("strikeDay", day + 1);
                if (day - since >= 3 && tag.getLong("challenged") <= 0) challenge(level, v, day);
                // A new leader since it began: it cuts the taxes at once (its first act).
                String was = tag.getString("leaderAtRevolt");
                UUID now = Villages.elder(id);
                if (now != null && !was.isEmpty() && !was.equals(now.toString())) {
                    TaxBook.Motion m = TaxBook.motion(level, v, true);
                    if (m != null) {
                        TaxBook.set(level, v, m.lever(), m.to(), "the new " + Government.title(id) + "'s first act");
                        changed(level, v, -1, m.lever());
                    }
                    tag.putString("leaderAtRevolt", now.toString());
                }
            }
        }
        CivicLand.changed();
    }

    // ------------------------------------------------------------------ who is angry

    /** How hard the taxes bite this folk: what it cares for, its purse, what it owes, its temper. */
    static int grievance(UUID village, VillageFolkEntity f) {
        if (f.isBaby() || f.isElder()) return 0;
        int[] w = Values.of(f);
        double g = 0.6 * w[Values.Value.WEALTH.ordinal()] + 0.3 * w[Values.Value.LEISURE.ordinal()] - 0.2 * w[Values.Value.TRADITION.ordinal()];
        if (Wealth.tier(f) == Wealth.Tier.POOR) g += 25;
        if (TaxBook.arrears(village, f.getUUID()) > 0) g += 25;
        if (f.life().has(Social.Trait.GRUMPY)) g += 15;
        if (f.life().has(Social.Trait.EASYGOING)) g -= 10;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD) g -= 40;      // the watch keeps the peace, not breaks it
        if (Knights.isKnight(village, f.getUUID())) g -= 50;
        // And the town's temper carries it: the heavier the taxes and the angrier its neighbours, the angrier it is.
        g += Math.max(0, burden(village) - 10) + level(village) / 4.0;
        return (int) Math.round(g + Math.floorMod(Objects.hash(f.getUUID(), "grievance"), 9));
    }

    /** The angriest, most first, up to so many: those it bites hardest, once the town is grumbling. */
    static List<VillageFolkEntity> angry(UUID village, List<VillageFolkEntity> grown, int most) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : grown) if (grievance(village, f) >= 30) out.add(f);
        out.sort(Comparator.comparingInt((VillageFolkEntity f) -> -grievance(village, f)));
        return out.size() > most ? new ArrayList<>(out.subList(0, most)) : out;
    }

    /** Is this folk one of the town's angry just now (the collector is not chosen from them)? */
    static boolean aggrieved(UUID village, VillageFolkEntity f) {
        if (level(village) < GRUMBLE) return false;
        Set<String> marked = new HashSet<>(CivicLand.read(CivicLand.list(CivicLand.town(village), "angry")));
        return marked.contains(f.getUUID().toString()) || grievance(village, f) >= 45;
    }

    /** Will it refuse the collector: in a revolt, one of the angry. */
    static boolean refuses(UUID village, VillageFolkEntity f) {
        return revolt(village) && aggrieved(village, f);
    }

    /** A refusal to the collector's face: the anger feeds on it. */
    static void refused(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        CompoundTag tag = CivicLand.town(v.id());
        tag.putInt("refusals", tag.getInt("refusals") + 1);
        CivicLand.changed();
    }

    static void taxed(VillageFolkEntity f, long day) {
        TAXED.put(f.getUUID(), day);
        if (TAXED.size() > 8192) TAXED.clear();
    }

    /** Does the leader send the watch with the collector: in a revolt, a lord or a stern leader, or the collector chased off last time. */
    static boolean guardsSent(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!revolt(id) || Patrols.watch(id).size() < 2) return false;
        CompoundTag tag = CivicLand.town(id);
        VillageFolkEntity leader = Orders.elderOf(id);
        boolean stern = Government.of(id) == Government.Form.LORD || leader != null && Values.top(leader) == Values.Value.SAFETY
            || tag.getBoolean("chased");
        if (stern) {
            Villages.tell(id, CivicLand.day(level), "the watch was sent to walk the tax collector's round");
            int u = Math.min(100, level(id) + 5);
            tag.putInt("unrest", u);
            CivicLand.changed();
        }
        return stern;
    }

    /** A vote on the taxes carried (TaxBook.enact): a cut settles the town, a rise stirs it. */
    static void changed(ServerLevel level, Villages.Village v, int dir, TaxBook.Lever lever) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        long day = CivicLand.day(level);
        int u = level(id) + (dir < 0 ? -15 : 8);
        tag.putInt("unrest", Math.max(0, Math.min(100, u)));
        tag.putLong(dir < 0 ? "cut" : "raised", day + 1);
        CivicLand.changed();
        if (dir < 0 && tag.getBoolean("revolt") && level(id) < REVOLT - 10) end(level, v, day, "the council gave way");
    }

    // ------------------------------------------------------------------ the protest

    /** The protest gathered now, before the hall: the angry with their signs, their ringleader, the leader called out. */
    static boolean begin(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> angry = angry(id, CivicLand.grown(id), 40);
        angry.removeIf(f -> f.isSleeping() || f.isHired() || Nether.away(f) || f.trip() != null);
        if (angry.size() < 3) return false;
        CompoundTag tag = CivicLand.town(id);
        ListTag marked = new ListTag();
        for (VillageFolkEntity f : angry) marked.add(StringTag.valueOf(f.getUUID().toString()));
        tag.put("angry", marked);
        tag.putLong("protestDay", day + 1);
        CivicLand.changed();
        CivicLand.Spot at = CivicLand.beforeTheHall(level, v);
        Assemblies.Assembly a = new Assemblies.Assembly(id, Assemblies.Kind.PROTEST, "", day, at.focus(), at.audience(), Assemblies.Layout.ARC);
        Set<UUID> invited = new HashSet<>();
        for (VillageFolkEntity f : angry) invited.add(f.getUUID());
        a.invited = invited;
        return Assemblies.beginNow(level, a);
    }

    /** Who leads it and who is called out (Assemblies.prepare): the angriest speaks for them; the leader comes to answer. */
    static void prepare(ServerLevel level, Assemblies.Assembly a) {
        List<VillageFolkEntity> angry = new ArrayList<>();
        if (a.invited != null) for (UUID u : a.invited) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f != null) angry.add(f);
        }
        angry.sort(Comparator.comparingInt((VillageFolkEntity f) -> -grievance(a.village, f)
            - (f.life().has(Social.Trait.SOCIABLE) ? 10 : 0) - (f.life().has(Social.Trait.GRUMPY) ? 8 : 0)));
        if (!angry.isEmpty()) a.host = angry.get(0).getUUID();
        VillageFolkEntity leader = Orders.elderOf(a.village);
        a.principals.clear();
        if (leader != null && !leader.getUUID().equals(a.host)) a.principals.add(leader.getUUID());
        Villages.Village v = Villages.get(a.village);
        if (v != null) signs(level, v, angry);
    }

    /** A sign in each protester's free hand: the stores' signs, or signs made of the stores' planks and a stick. */
    static int signs(ServerLevel level, Villages.Village v, List<VillageFolkEntity> angry) {
        int handed = 0;
        for (VillageFolkEntity f : angry) {
            boolean off = f.getOffhandItem().isEmpty(), main = f.getMainHandItem().isEmpty();
            if (!off && !main) continue;
            if (Market.stock(level, v.id(), s -> s.is(ItemTags.SIGNS)) <= 0) {
                // Three signs from six planks and a stick, as a player makes them.
                if (Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) < 6 || Market.stock(level, v.id(), s -> s.is(Items.STICK)) < 1) break;
                if (!TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 6) || !TownWork.take(level, v, s -> s.is(Items.STICK), 1)) break;
                TownWork.give(level, v, new ItemStack(Items.OAK_SIGN, 3));
            }
            if (!TownWork.take(level, v, s -> s.is(ItemTags.SIGNS), 1)) break;
            f.setItemInHand(off ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_SIGN));
            SIGNS.put(f.getUUID(), v.id());
            handed++;
        }
        return handed;
    }

    /** The signs had back into the stores at the end. */
    static int signsBack(ServerLevel level, Villages.Village v) {
        int back = 0;
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(SIGNS.entrySet())) {
            if (!e.getValue().equals(v.id())) continue;
            SIGNS.remove(e.getKey());
            VillageFolkEntity f = CivicLand.find(level, e.getKey());
            if (f == null) continue;
            for (InteractionHand h : InteractionHand.values()) {
                ItemStack s = f.getItemInHand(h);
                if (!s.is(ItemTags.SIGNS)) continue;
                f.setItemInHand(h, ItemStack.EMPTY);
                ItemStack left = Market.intoStores(level, v.id(), s);
                if (!left.isEmpty()) f.spawnAtLocation(left);
                back++;
                break;
            }
        }
        return back;
    }

    /** Is this folk holding one of a protest's signs? */
    public static boolean holdsSign(VillageFolkEntity f) {
        return SIGNS.containsKey(f.getUUID());
    }

    /** What is said (Assemblies.script): the chants, the ringleader's case, the council's answer by its vote, the last word. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        UUID id = a.village;
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        long day = CivicLand.day(level);
        TaxBook.Motion m = TaxBook.motion(level, v, true);
        TaxBook.Lever hated = m != null ? m.lever() : TaxBook.Lever.HEAD;
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "We've come about the taxes, and we'll be heard!", "Come out! Come out and hear us!",
            "We want a word with the council — about the taxes!"), '!', null));
        List<UUID> crowd = new ArrayList<>(a.invited == null ? Set.of() : a.invited);
        crowd.remove(a.host);
        crowd.sort(Comparator.comparing(UUID::toString));
        String[] chants = switch (hated) {
            case HEAD -> new String[]{ "No more head tax!", "Not a coin more!", "Bread, not taxes!" };
            case LEVY -> new String[]{ "Pay for your own war!", "No levy!", "Our coin, our lives!" };
            case TOLL -> new String[]{ "Free markets for free folk!", "No toll on honest trade!", "Let us sell in peace!" };
            case WAGE -> new String[]{ "Hands off our wages!", "A full wage for a full day!", "Not a penny more!" };
        };
        for (int i = 0; i < Math.min(3, crowd.size()); i++) s.add(new Assemblies.Line(crowd.get(i), chants[i % chants.length], '!', null));
        List<String> why = CivicLand.read(CivicLand.list(CivicLand.town(id), "why"));
        String cause = why.isEmpty() ? "the taxes are more than we can bear" : why.get(0);
        s.add(new Assemblies.Line(null, "Hear us: " + cause + ". Half of us can't pay, and the rest can't spend!", '?', null));
        UUID answers = a.principals.isEmpty() ? null : a.principals.get(0);
        String lead = answers == null ? "The council sends word: " : "";
        if (m == null) {
            s.add(new Assemblies.Line(answers, lead + "There's nothing left to cut. The taxes are as low as they go.", '?', () -> answered(level, v, day, false)));
            s.add(new Assemblies.Line(null, "Then why are we still paying?", '?', null));
            return;
        }
        TaxBook.Tally t = TaxBook.tally(level, v, m);
        if (t.carried()) {
            s.add(new Assemblies.Line(answers, lead + "You're heard. The council gives way, " + t.ayes() + " to " + t.nays() + ": " + m.lever().words
                + " comes down to " + TaxBook.words(m.lever(), m.to()) + ".", '!', () -> {
                    TaxBook.enact(level, v, t, day, "the council, answering the protest before the hall,");
                    answered(level, v, day, true);
                }));
            s.add(new Assemblies.Line(null, FolkTalk.pick(r, "We'll hold you to it!", "That's more like it!", "About time!"), '!', null));
        } else {
            s.add(new Assemblies.Line(answers, lead + "The town needs its purse. The council won't give way, " + t.nays() + " to " + t.ayes()
                + ": the taxes stand.", '?', () -> {
                    TaxBook.enact(level, v, t, day, "the council, answering the protest before the hall,");
                    answered(level, v, day, false);
                }));
            s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Then we'll not pay! Not a coin!", "You'll be sorry for this!",
                "We'll see who needs whom!"), '?', null));
        }
    }

    /** The council's answer to the protest, into the books. */
    static void answered(ServerLevel level, Villages.Village v, long day, boolean gaveWay) {
        CompoundTag tag = CivicLand.town(v.id());
        tag.putString("answer", gaveWay ? "the council gave way" : "the council would not give way");
        if (!gaveWay) {
            tag.putLong("refusedDay", day + 1);
            tag.putInt("unrest", Math.min(100, level(v.id()) + 5));
        }
        CivicLand.changed();
    }

    /** It is over (Assemblies.close): the signs back, into the chronicle, and every protester remembers it. */
    static void closed(ServerLevel level, Assemblies.Assembly a) {
        Villages.Village v = Villages.get(a.village);
        if (v == null) return;
        long day = CivicLand.day(level);
        int back = signsBack(level, v);
        int n = a.seated.size() + (a.host == null ? 0 : 1);
        String answer = CivicLand.town(a.village).getString("answer");
        Villages.tell(a.village, day, n + " folk protested the taxes before " + (Villages.builtStructure(a.village, "hall") != null ? "the meeting hall" : "the board")
            + (answer.isEmpty() ? "" : "; " + answer) + (back > 0 ? "" : ""));
        for (UUID u : a.seated.keySet()) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f != null) f.persona().remember(day, "I protested the taxes before the hall", 4);
        }
        CompoundTag tag = CivicLand.town(a.village);
        tag.putInt("protests", tag.getInt("protests") + 1);
        CivicLand.changed();
    }

    // ------------------------------------------------------------------ the revolt

    /** The town rises: into the chronicle and the morning's assembly; a strike tomorrow. */
    static void rise(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        if (tag.getBoolean("revolt")) return;
        tag.putBoolean("revolt", true);
        tag.putLong("revoltSince", day + 1);
        tag.putLong("strikeDay", day + 2);
        tag.remove("challenged");
        UUID leader = Villages.elder(id);
        tag.putString("leaderAtRevolt", leader == null ? "" : leader.toString());
        if (CivicLand.list(tag, "angry").isEmpty()) {
            ListTag marked = new ListTag();
            for (VillageFolkEntity f : angry(id, CivicLand.grown(id), 40)) marked.add(StringTag.valueOf(f.getUUID().toString()));
            tag.put("angry", marked);
        }
        CivicLand.changed();
        Villages.tell(id, day, "the town rose against its taxes: " + CivicLand.list(tag, "angry").size() + " folk will not pay, and they strike tomorrow");
        Market.assemblyNews(id, "There's trouble: half the town says it won't pay a coin of tax, and they mean to down tools tomorrow.");
    }

    /** It is over: into the chronicle; the angry are let alone. */
    static void end(ServerLevel level, Villages.Village v, long day, String why) {
        CompoundTag tag = CivicLand.town(v.id());
        if (!tag.getBoolean("revolt")) return;
        tag.putBoolean("revolt", false);
        tag.remove("strikeDay");
        tag.remove("angry");
        tag.remove("refusedDay");
        tag.putInt("hot", 0);
        CivicLand.changed();
        Villages.tell(v.id(), day, "the tax revolt ended: " + why);
        Market.assemblyNews(v.id(), "The tax trouble's over: " + why + ". Back to work, all.");
    }

    /**
     * Three days of it: the leader challenged. An election called early where the town elects its leader; a vote of no
     * confidence in a lordship; under the chaplain's rule, nothing but the anger.
     */
    static void challenge(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        tag.putLong("challenged", day + 1);
        CivicLand.changed();
        Government.Form form = Government.of(id);
        if (form == Government.Form.LORD) {
            Identity.Rec r = Identity.known(id);
            if (r != null && !Government.asked(id)) {
                Government.callDepose(level, v, r, day);
                Villages.tell(id, day, "the tax revolt brought the lord to a vote of no confidence");
            }
            return;
        }
        if (!Government.holdsElections(id)) {
            Villages.tell(id, day, "the revolt raged on: the " + Government.title(id) + " is answerable to nobody's vote");
            return;
        }
        Elections.Campaign c = Elections.campaign(id);
        if (c != null && !c.counted) return;                                // an election is under way already
        long vote = day + 2;
        String saved = Ledger.note(id, "election.next");
        long next = vote + 1;
        try { next = saved == null || saved.isEmpty() ? next : Long.parseLong(saved); } catch (NumberFormatException ignored) { }
        if (next <= vote) return;
        Ledger.note(id, "election.next", Long.toString(vote));
        Villages.tell(id, day, "the tax revolt forced an early election: the town chooses its " + Government.title(id) + " on day " + vote);
        Market.assemblyNews(id, "The council has bowed to the town: we choose our " + Government.title(id) + " again, on day " + vote + ".");
        UUID p = PlayerLeader.leaderId(id);
        if (p != null) CivicLand.tell(level.getServer(), p, "The tax revolt in " + Villages.name(id) + " has forced an early election, on day " + vote
            + ". Stand on your record.", net.minecraft.ChatFormatting.RED);
    }

    /** At the polls (Elections.judge): the one who led through the revolt answers for it; one who stands for wages is heard. */
    public static double electionLean(ServerLevel level, VillageFolkEntity voter, Elections.Candidate c) {
        UUID id = voter.ownerId();
        int u = level(id);
        if (id == null || u < GRUMBLE) return 0;
        double lean = 0;
        if (c.id().equals(Villages.elder(id))) lean -= (u - 30) / 2.0 * (1 + grievance(id, voter) / 100.0);
        if (c.platform() == Values.Value.WEALTH || c.second() == Values.Value.WEALTH) lean += (u - 30) / 3.0;
        return lean;
    }

    // ------------------------------------------------------------------ the strike

    /** Is this folk on strike now (VillageFolkEntity.onShift): one of the angry, the strike's day, by day. */
    public static boolean striking(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.stationTask() == AssistantEntity.StationTask.GUARD || !revolt(id)) return false;
        CompoundTag tag = CivicLand.town(id);
        long day = f.level().getDayTime() / 24000L, t = f.level().getDayTime() % 24000L;
        if (tag.getLong("strikeDay") != day + 1 || t < 1000 || t > 11500) return false;
        return CivicLand.read(CivicLand.list(tag, "angry")).contains(f.getUUID().toString());
    }

    /** A striker's day (VillageFolkEntity.bedtime): a picket before the hall, a chant now and then. True while it is at it. */
    public static boolean picket(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !striking(f)) return false;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return false;
        BlockPos hall = TaxBook.hall(level, v);
        int slot = Math.floorMod(f.getUUID().hashCode(), 12);
        double ang = Math.PI * 2 * slot / 12.0;
        BlockPos at = hall.offset((int) Math.round(Math.cos(ang) * 4), 0, (int) Math.round(Math.sin(ang) * 4));
        f.hobbyNow = "on strike against the taxes";
        f.lastLeisureTick = f.tickCount;
        if (!Civics.goTo(f, at, 1.5, 0.8)) return true;
        f.getLookControl().setLookAt(hall.getX() + 0.5, hall.getY() + 1.5, hall.getZ() + 0.5);
        if (f.tickCount % 200 == 0 && f.getRandom().nextInt(4) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "On strike! No work till the taxes come down!", "Down tools! Down taxes!",
                "Not a stroke of work today!", "The council can dig its own fields!"));
            f.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    // ------------------------------------------------------------------ what it does to the town and its folk

    /** What the taxes do to the town's spirits (Contentment.compute): ten down at the worst; a little up for light ones. */
    public static int contentment(UUID village, List<String> good, List<String> bad) {
        if (!CivicLand.live() || !CivicLand.known(village)) return 0;
        int u = level(village);
        int n = -Math.min(10, u / 8);
        if (revolt(village)) bad.add("a tax revolt");
        else if (u >= GRUMBLE) bad.add("the taxes are heavy");
        if (u < 15 && burden(village) <= 12) {
            n += 2;
            good.add("light taxes");
        }
        return n;
    }

    /** Its spirits (VillageFolkEntity.refreshMood): the taxes cut, or the bite of them and of what it owes. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID id = f.ownerId();
        if (id == null || !CivicLand.live() || !CivicLand.known(id) || f.isBaby()) return m;
        CompoundTag tag = CivicLand.town(id);
        if (day + 1 - tag.getLong("cut") <= 2 && tag.getLong("cut") > 0) {
            m += 4;
            why.add(new Object[]{ "taxcut", 4 });
        }
        Long paid = TAXED.get(f.getUUID());
        if (paid != null && day - paid <= 1 && level(id) >= GRUMBLE && grievance(id, f) >= 30) {
            m -= 4;
            why.add(new Object[]{ "taxed", 4 });
        }
        if (TaxBook.arrears(id, f.getUUID()) > 0) {
            m -= 3;
            why.add(new Object[]{ "arrears", 3 });
        }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        RandomSource r = f.getRandom();
        return switch (why) {
            case "taxcut" -> FolkTalk.pick(r, "The council's cut the taxes! There's a bit more in my purse.", "Lower taxes. About time, and I'm glad of it.");
            case "taxed" -> FolkTalk.pick(r, "Tax day took what little I had.", "The collector's been. My purse is lighter and my heart's heavier.");
            case "arrears" -> FolkTalk.pick(r, "I owe the town back taxes, and it sits on me.", "I'm behind with my taxes. I lie awake over it.");
            default -> "";
        };
    }

    /** A grumble at the taxes, in its own words. */
    static String grumble(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (revolt(id)) return FolkTalk.pick(r, "Not a coin! Not till they come down!", "Let them come for it. They'll get nothing from me.",
            "The council can whistle for its taxes.");
        if (TaxBook.arrears(id, f.getUUID()) > 0) return FolkTalk.pick(r, "Behind with my taxes again. How's a body to live?",
            "They'll have the shirt off my back next.");
        return FolkTalk.pick(r, "These taxes will be the ruin of us.", "Two coins a head! For what?", "The council's got its hand in every purse.",
            "I'd buy a pie, but the collector's coming.", "Taxes, taxes. It's all anybody talks about.");
    }

    /** What it thinks of the taxes, for the talk ("What are the taxes here?"). */
    static String opinion(VillageFolkEntity f) {
        UUID id = f.ownerId();
        int u = level(id);
        if (revolt(id)) return aggrieved(id, f) ? "And we're not paying them. Not a coin." : "Half the town's refusing to pay. It'll end badly.";
        if (u >= PROTEST) return "Folk are furious about them. There'll be trouble.";
        if (u >= GRUMBLE) return grievance(id, f) >= 30 ? "Too much, if you ask me." : "Folk grumble, but the town has to be paid for.";
        return "Folk pay them without much fuss.";
    }

    // ------------------------------------------------------------------ the books' page

    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        CompoundTag out = new CompoundTag();
        out.putInt("level", level(id));
        out.putInt("target", tag.getInt("target"));
        out.putString("word", word(id));
        out.putInt("burden", burden(id));
        out.put("why", CivicLand.list(tag, "why").copy());
        out.putBoolean("revolt", revolt(id));
        out.putLong("revoltSince", tag.getLong("revoltSince") - 1);
        out.putLong("strikeDay", tag.getLong("strikeDay") - 1);
        out.putLong("protestDay", tag.getLong("protestDay") - 1);
        out.putLong("protestDue", tag.getLong("protestDue") - 1);
        out.putString("answer", tag.getString("answer"));
        out.putInt("angry", CivicLand.list(tag, "angry").size());
        out.putInt("refusals", tag.getInt("refusals"));
        out.putInt("protests", tag.getInt("protests"));
        out.putLong("challenged", tag.getLong("challenged") - 1);
        return out;
    }

    // ------------------------------------------------------------------ an operator's hand, and the tests

    /** A protest now (an operator; the tests): the unrest put past sixty and the angry gathered at once. */
    public static String protestNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        tag.putInt("unrest", Math.max(level(id), PROTEST + 5));
        if (CivicLand.read(CivicLand.list(tag, "why")).isEmpty()) tag.put("why", CivicLand.strings(List.of("the taxes are more than we can bear")));
        CivicLand.changed();
        if (Assemblies.underWay(id) != null) return "Something is under way in " + Villages.name(id) + " already; the protest will follow.";
        return begin(level, v, CivicLand.day(level)) ? "The angry of " + Villages.name(id) + " gather before the hall to protest the taxes."
            : "Fewer than three of " + Villages.name(id) + " are angry enough to protest.";
    }

    /** A revolt now (an operator; the tests): the unrest past seventy-five and the town risen. */
    public static String revoltNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        tag.putInt("unrest", Math.max(level(id), REVOLT + 10));
        tag.putLong("refusedDay", CivicLand.day(level) + 1);
        CivicLand.changed();
        rise(level, v, CivicLand.day(level));
        return Villages.name(id) + " has risen against its taxes: " + CivicLand.list(tag, "angry").size() + " folk refuse to pay.";
    }

    /** Calm (an operator): the unrest gone and any revolt over. */
    public static String calmNow(ServerLevel level, Villages.Village v) {
        CompoundTag tag = CivicLand.town(v.id());
        end(level, v, CivicLand.day(level), "by decree");
        tag.putInt("unrest", 0);
        tag.remove("protestDue");
        tag.remove("why");
        CivicLand.changed();
        return Villages.name(v.id()) + " is calm again.";
    }

    /** Tests: the council has given way (a tax lowered): what that does to the unrest and a revolt. */
    public static void councilGaveWayForTests(ServerLevel level, Villages.Village v) {
        changed(level, v, -1, TaxBook.Lever.HEAD);
    }

    /** Tests: the angry of the town, most first. */
    public static List<VillageFolkEntity> angryForTests(UUID village) {
        return angry(village, CivicLand.grown(village), 99);
    }

    /** Tests: the unrest set outright. */
    public static void setForTests(UUID village, int unrest) {
        CivicLand.town(village).putInt("unrest", Math.max(0, Math.min(100, unrest)));
        CivicLand.changed();
    }

    /** Tests: the morning's look, now. */
    public static void dailyForTests(ServerLevel level, Villages.Village v) {
        daily(level, v, CivicLand.day(level));
    }

    /** Tests: today a strike day. */
    public static void strikeTodayForTests(ServerLevel level, UUID village) {
        CivicLand.town(village).putLong("strikeDay", CivicLand.day(level) + 1);
        CivicLand.changed();
    }

    /** Tests: the reasons given for the unrest. */
    public static List<String> whyForTests(UUID village) {
        return CivicLand.read(CivicLand.list(CivicLand.town(village), "why"));
    }

    /** Tests: when the election is now to be (Ledger's "election.next"), or -1. */
    public static long electionForTests(UUID village) {
        String s = Ledger.note(village, "election.next");
        try { return s == null || s.isEmpty() ? -1 : Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    /** Tests: the leader challenged now. */
    public static void challengeForTests(ServerLevel level, Villages.Village v) {
        challenge(level, v, CivicLand.day(level));
    }
}
